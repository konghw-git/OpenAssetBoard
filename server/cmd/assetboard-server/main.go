// Command assetboard-server is the self-hosted account and snapshot service for 日盈.
//
//	assetboard-server serve
//	assetboard-server user add <username>
//	assetboard-server user passwd <username>
//	assetboard-server user list
//	assetboard-server user disable|enable <username>
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"text/tabwriter"
	"time"

	"golang.org/x/term"

	"github.com/konghw-git/OpenAssetBoard/server/internal/api"
	"github.com/konghw-git/OpenAssetBoard/server/internal/auth"
	"github.com/konghw-git/OpenAssetBoard/server/internal/store"
)

const version = "0.1.0"

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func run(args []string) error {
	if len(args) == 0 {
		return serve(nil)
	}
	switch args[0] {
	case "serve":
		return serve(args[1:])
	case "user":
		return userCommand(args[1:])
	case "backup":
		return backupCommand(args[1:])
	case "version":
		fmt.Println(version)
		return nil
	case "help", "-h", "--help":
		usage()
		return nil
	default:
		usage()
		return fmt.Errorf("unknown command %q", args[0])
	}
}

func usage() {
	fmt.Fprint(os.Stderr, `日盈 account and sync server

Usage:
  assetboard-server serve [flags]
  assetboard-server user add [flags] <username>
  assetboard-server user passwd [flags] <username>
  assetboard-server user list [flags]
  assetboard-server user disable [flags] <username>
  assetboard-server user enable [flags] <username>
  assetboard-server backup [flags] -out <file>
  assetboard-server version

Flags must come before the username (Go's flag package stops at the first positional).

Serve environment:
  ASSETBOARD_ADDR           listen address (default :8090)
  ASSETBOARD_DATA_DIR       data directory (default ./data)
  ASSETBOARD_DB             database path (default <data-dir>/assetboard.db)
  ASSETBOARD_ACCESS_TTL     access token lifetime (default 15m)
  ASSETBOARD_REFRESH_TTL    refresh token lifetime (default 720h)
  ASSETBOARD_MAX_BLOB_BYTES maximum snapshot size (default 33554432)
  ASSETBOARD_HISTORY        snapshots kept per user (default 10)
  ASSETBOARD_TRUST_PROXY    trust X-Forwarded-For (default false; true behind Caddy)

Passwords are read from stdin, never from an argument.
`)
}

// ---------------------------------------------------------------- serve

func serve(args []string) error {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	addr := fs.String("addr", envOr("ASSETBOARD_ADDR", ":8090"), "listen address")
	dataDir := fs.String("data-dir", envOr("ASSETBOARD_DATA_DIR", "./data"), "data directory")
	dbPath := fs.String("db", "", "database path (default <data-dir>/assetboard.db, or ASSETBOARD_DB)")
	history := fs.Int("history", envInt("ASSETBOARD_HISTORY", 10), "snapshots kept per user")
	trustProxy := fs.Bool("trust-proxy", envBool("ASSETBOARD_TRUST_PROXY", false), "trust X-Forwarded-For")
	_ = fs.Parse(args)
	if *dbPath == "" {
		if p := os.Getenv("ASSETBOARD_DB"); p != "" {
			*dbPath = p
		} else {
			*dbPath = filepath.Join(*dataDir, "assetboard.db")
		}
	}

	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)

	st, err := openStore(*dbPath, *history)
	if err != nil {
		return err
	}
	defer st.Close()

	server, err := api.New(api.Config{
		Store:        st,
		AccessTTL:    envDuration("ASSETBOARD_ACCESS_TTL", 15*time.Minute),
		RefreshTTL:   envDuration("ASSETBOARD_REFRESH_TTL", 30*24*time.Hour),
		MaxBlobBytes: envInt64("ASSETBOARD_MAX_BLOB_BYTES", 32<<20),
		TrustProxy:   *trustProxy,
		Logger:       logger,
		Version:      version,
	})
	if err != nil {
		return err
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if err := st.PurgeExpired(context.Background(), time.Now()); err != nil {
		logger.Warn("purge expired tokens", "error", err)
	}
	go purgeLoop(ctx, st, logger)

	httpServer := &http.Server{
		Addr:              *addr,
		Handler:           server.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		// A 32 MiB upload/download over a slow mobile link is the worst case; five minutes
		// still bounds what one idle authenticated client can hold.
		ReadTimeout:    5 * time.Minute,
		WriteTimeout:   5 * time.Minute,
		IdleTimeout:    2 * time.Minute,
		MaxHeaderBytes: 1 << 20,
	}

	errCh := make(chan error, 1)
	go func() {
		logger.Info("server listening", "addr", *addr, "db", *dbPath, "version", version)
		if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
	}()

	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
		logger.Info("shutting down")
	}
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return httpServer.Shutdown(shutdownCtx)
}

func purgeLoop(ctx context.Context, st *store.Store, logger *slog.Logger) {
	ticker := time.NewTicker(time.Hour)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-ticker.C:
			if err := st.PurgeExpired(context.Background(), now); err != nil {
				logger.Warn("purge expired tokens", "error", err)
			}
		}
	}
}

// ---------------------------------------------------------------- admin commands

func userCommand(args []string) error {
	if len(args) == 0 {
		usage()
		return errors.New("missing user command (add, passwd, list, disable, enable)")
	}
	switch args[0] {
	case "add":
		return userAdd(args[1:])
	case "passwd":
		return userPasswd(args[1:])
	case "list":
		return userList(args[1:])
	case "disable", "enable":
		return userToggle(args[1:], args[0] == "enable")
	default:
		usage()
		return fmt.Errorf("unknown user command %q", args[0])
	}
}

func userAdd(args []string) error {
	fs := flag.NewFlagSet("user add", flag.ContinueOnError)
	db := fs.String("db", defaultDBPath(""), "database path")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 1 {
		return errors.New("usage: assetboard-server user add [flags] <username>")
	}
	username := strings.TrimSpace(fs.Arg(0))
	if !auth.ValidUsername(username) {
		return errors.New("username must be 3-32 characters: letters, digits, dot, underscore or dash")
	}
	password, err := readPassword("Password: ")
	if err != nil {
		return err
	}
	if isTerminal() {
		again, err := readPassword("Repeat password: ")
		if err != nil {
			return err
		}
		if password != again {
			return errors.New("passwords do not match")
		}
	}
	if !auth.ValidPassword(password) {
		return errors.New("password must be 8-256 bytes")
	}
	hash, err := auth.NewHasher().Hash(password)
	if err != nil {
		return err
	}
	st, err := openStore(*db, 10)
	if err != nil {
		return err
	}
	defer st.Close()
	user, err := st.CreateUser(context.Background(), username, hash, time.Now())
	if errors.Is(err, store.ErrDuplicateUsername) {
		return fmt.Errorf("user %q already exists", username)
	}
	if err != nil {
		return err
	}
	fmt.Printf("created %s (%s)\n", user.Username, user.ID)
	return nil
}

func userPasswd(args []string) error {
	fs := flag.NewFlagSet("user passwd", flag.ContinueOnError)
	db := fs.String("db", defaultDBPath(""), "database path")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 1 {
		return errors.New("usage: assetboard-server user passwd [flags] <username>")
	}
	username := strings.TrimSpace(fs.Arg(0))
	password, err := readPassword("New password: ")
	if err != nil {
		return err
	}
	if isTerminal() {
		again, err := readPassword("Repeat password: ")
		if err != nil {
			return err
		}
		if password != again {
			return errors.New("passwords do not match")
		}
	}
	if !auth.ValidPassword(password) {
		return errors.New("password must be 8-256 bytes")
	}
	hash, err := auth.NewHasher().Hash(password)
	if err != nil {
		return err
	}
	st, err := openStore(*db, 10)
	if err != nil {
		return err
	}
	defer st.Close()
	user, err := st.UserByUsername(context.Background(), username)
	if errors.Is(err, store.ErrNotFound) {
		return fmt.Errorf("user %q not found", username)
	}
	if err != nil {
		return err
	}
	if err := st.SetPassword(context.Background(), user.ID, hash); err != nil {
		return err
	}
	// A password change ends every open session; otherwise an old refresh token keeps
	// working after an admin was told the password leaked.
	if err := st.RevokeUserTokens(context.Background(), user.ID, time.Now()); err != nil {
		return err
	}
	fmt.Printf("password changed for %s; all sessions were revoked\n", user.Username)
	return nil
}

func userList(args []string) error {
	fs := flag.NewFlagSet("user list", flag.ContinueOnError)
	db := fs.String("db", defaultDBPath(""), "database path")
	if err := fs.Parse(args); err != nil {
		return err
	}
	st, err := openStore(*db, 10)
	if err != nil {
		return err
	}
	defer st.Close()
	users, err := st.ListUsers(context.Background())
	if err != nil {
		return err
	}
	tw := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(tw, "USERNAME\tID\tSTATUS\tCREATED")
	for _, u := range users {
		status := "active"
		if u.Disabled {
			status = "disabled"
		}
		fmt.Fprintf(tw, "%s\t%s\t%s\t%s\n", u.Username, u.ID, status, u.CreatedAt.Format(time.RFC3339))
	}
	return tw.Flush()
}

func userToggle(args []string, enable bool) error {
	name := "disable"
	if enable {
		name = "enable"
	}
	fs := flag.NewFlagSet("user "+name, flag.ContinueOnError)
	db := fs.String("db", defaultDBPath(""), "database path")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 1 {
		return fmt.Errorf("usage: assetboard-server user %s [flags] <username>", name)
	}
	username := strings.TrimSpace(fs.Arg(0))
	st, err := openStore(*db, 10)
	if err != nil {
		return err
	}
	defer st.Close()
	user, err := st.UserByUsername(context.Background(), username)
	if errors.Is(err, store.ErrNotFound) {
		return fmt.Errorf("user %q not found", username)
	}
	if err != nil {
		return err
	}
	if err := st.SetDisabled(context.Background(), user.ID, !enable); err != nil {
		return err
	}
	if !enable {
		if err := st.RevokeUserTokens(context.Background(), user.ID, time.Now()); err != nil {
			return err
		}
	}
	fmt.Printf("%s %s\n", map[bool]string{true: "enabled", false: "disabled"}[enable], user.Username)
	return nil
}

func backupCommand(args []string) error {
	fs := flag.NewFlagSet("backup", flag.ContinueOnError)
	db := fs.String("db", defaultDBPath(""), "database path")
	out := fs.String("out", "", "output file; must not already exist")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *out == "" {
		return errors.New("usage: assetboard-server backup [flags] -out <file>")
	}
	st, err := openStore(*db, 10)
	if err != nil {
		return err
	}
	defer st.Close()
	if err := st.BackupTo(context.Background(), *out); err != nil {
		return err
	}
	fmt.Printf("backup written to %s\n", *out)
	return nil
}

// ---------------------------------------------------------------- helpers

func openStore(path string, history int) (*store.Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, fmt.Errorf("create data directory: %w", err)
	}
	return store.Open(path, store.Options{History: history})
}

func defaultDBPath(dataDir string) string {
	if p := os.Getenv("ASSETBOARD_DB"); p != "" {
		return p
	}
	if dataDir == "" {
		dataDir = envOr("ASSETBOARD_DATA_DIR", "./data")
	}
	return filepath.Join(dataDir, "assetboard.db")
}

func isTerminal() bool { return term.IsTerminal(int(os.Stdin.Fd())) }

func readPassword(prompt string) (string, error) {
	if isTerminal() {
		fmt.Fprint(os.Stderr, prompt)
		b, err := term.ReadPassword(int(os.Stdin.Fd()))
		fmt.Fprintln(os.Stderr)
		if err != nil {
			return "", err
		}
		return string(b), nil
	}
	b, err := io.ReadAll(io.LimitReader(os.Stdin, 4097))
	if err != nil {
		return "", err
	}
	if len(b) > 4096 {
		return "", errors.New("password input is too long")
	}
	return strings.TrimRight(string(b), "\r\n"), nil
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envBool(key string, def bool) bool {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	b, err := strconv.ParseBool(v)
	if err != nil {
		fmt.Fprintf(os.Stderr, "warning: %s=%q is not a boolean, using %v\n", key, v, def)
		return def
	}
	return b
}

func envInt(key string, def int) int {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		fmt.Fprintf(os.Stderr, "warning: %s=%q is not an integer, using %d\n", key, v, def)
		return def
	}
	return n
}

func envInt64(key string, def int64) int64 {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil {
		fmt.Fprintf(os.Stderr, "warning: %s=%q is not an integer, using %d\n", key, v, def)
		return def
	}
	return n
}

func envDuration(key string, def time.Duration) time.Duration {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	d, err := time.ParseDuration(v)
	if err != nil {
		fmt.Fprintf(os.Stderr, "warning: %s=%q is not a duration, using %s\n", key, v, def)
		return def
	}
	return d
}
