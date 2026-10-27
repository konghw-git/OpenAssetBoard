// Package api is the HTTP surface of the asset-board server: authentication, plus one opaque
// snapshot per user. It never parses or rewrites the ledger itself; the backup file is the
// client's format and the server only stores it.
package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/konghw-git/OpenAssetBoard/server/internal/auth"
	"github.com/konghw-git/OpenAssetBoard/server/internal/store"
)

type Config struct {
	Store        *store.Store
	Hasher       *auth.Hasher
	AccessTTL    time.Duration
	RefreshTTL   time.Duration
	MaxBlobBytes int64
	TrustProxy   bool
	Now          func() time.Time
	Logger       *slog.Logger
	Version      string

	LoginWindow    time.Duration
	LoginIPLimit   int
	LoginUserLimit int
	// PasswordUserLimit bounds how often one signed-in user may try to change their password
	// with a wrong old password, so a stolen access token cannot be turned into a password.
	PasswordUserLimit int
}

type Server struct {
	store           *store.Store
	hasher          *auth.Hasher
	accessTTL       time.Duration
	refreshTTL      time.Duration
	maxBlob         int64
	trustProxy      bool
	now             func() time.Time
	log             *slog.Logger
	version         string
	ipLimiter       *rateLimiter
	userLimiter     *rateLimiter
	passwordLimiter *rateLimiter
	dummyHash       string
}

func New(cfg Config) (*Server, error) {
	if cfg.Store == nil {
		return nil, errors.New("store is required")
	}
	if cfg.Hasher == nil {
		cfg.Hasher = auth.NewHasher()
	}
	if cfg.AccessTTL <= 0 {
		cfg.AccessTTL = 15 * time.Minute
	}
	if cfg.RefreshTTL <= 0 {
		cfg.RefreshTTL = 30 * 24 * time.Hour
	}
	if cfg.MaxBlobBytes <= 0 {
		cfg.MaxBlobBytes = 32 << 20
	}
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	if cfg.Logger == nil {
		cfg.Logger = slog.Default()
	}
	if cfg.Version == "" {
		cfg.Version = "dev"
	}
	if cfg.LoginWindow <= 0 {
		cfg.LoginWindow = 5 * time.Minute
	}
	if cfg.LoginIPLimit <= 0 {
		cfg.LoginIPLimit = 20
	}
	if cfg.LoginUserLimit <= 0 {
		cfg.LoginUserLimit = 5
	}
	if cfg.PasswordUserLimit <= 0 {
		cfg.PasswordUserLimit = 5
	}
	dummy, err := cfg.Hasher.Hash("dummy-password-that-is-never-valid")
	if err != nil {
		return nil, fmt.Errorf("prepare login timing hash: %w", err)
	}
	return &Server{
		store:           cfg.Store,
		hasher:          cfg.Hasher,
		accessTTL:       cfg.AccessTTL,
		refreshTTL:      cfg.RefreshTTL,
		maxBlob:         cfg.MaxBlobBytes,
		trustProxy:      cfg.TrustProxy,
		now:             cfg.Now,
		log:             cfg.Logger,
		version:         cfg.Version,
		ipLimiter:       newRateLimiter(cfg.LoginIPLimit, cfg.LoginWindow),
		userLimiter:     newRateLimiter(cfg.LoginUserLimit, cfg.LoginWindow),
		passwordLimiter: newRateLimiter(cfg.PasswordUserLimit, cfg.LoginWindow),
		dummyHash:       dummy,
	}, nil
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.health)
	mux.HandleFunc("POST /v1/auth/login", s.login)
	mux.HandleFunc("POST /v1/auth/refresh", s.refresh)
	mux.HandleFunc("POST /v1/auth/logout", s.logout)
	mux.Handle("POST /v1/auth/password", s.requireAuth(s.changePassword))
	mux.Handle("GET /v1/me", s.requireAuth(s.me))
	mux.Handle("GET /v1/sync/meta", s.requireAuth(s.syncMeta))
	mux.Handle("GET /v1/sync", s.requireAuth(s.getSync))
	mux.Handle("PUT /v1/sync", s.requireAuth(s.putSync))
	mux.Handle("GET /v1/sync/revisions", s.requireAuth(s.listRevisions))
	mux.Handle("GET /v1/sync/revisions/{revision}", s.requireAuth(s.getRevision))

	var h http.Handler = mux
	h = s.securityHeaders(h)
	h = s.logRequests(h)
	h = s.recover(h)
	return h
}

// ---------------------------------------------------------------- middleware

func (s *Server) recover(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				s.log.Error("panic serving request", "method", r.Method, "path", r.URL.Path, "panic", rec)
				writeError(w, http.StatusInternalServerError, "internal", "internal error")
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func (s *Server) securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
	bytes  int
}

func (r *statusRecorder) WriteHeader(code int) {
	if r.status == 0 {
		r.status = code
	}
	r.ResponseWriter.WriteHeader(code)
}

func (r *statusRecorder) Write(b []byte) (int, error) {
	if r.status == 0 {
		r.status = http.StatusOK
	}
	n, err := r.ResponseWriter.Write(b)
	r.bytes += n
	return n, err
}

func (s *Server) logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w}
		next.ServeHTTP(rec, r)
		if rec.status == 0 {
			rec.status = http.StatusOK
		}
		s.log.Info("request",
			"method", r.Method,
			"path", r.URL.Path,
			"status", rec.status,
			"bytes", rec.bytes,
			"duration_ms", time.Since(start).Milliseconds(),
			"ip", s.clientIP(r),
		)
	})
}

// requireAuth resolves the bearer token to a user and hands that user to the handler. The
// user id always comes from here, never from a path, query or body.
func (s *Server) requireAuth(next func(http.ResponseWriter, *http.Request, store.User)) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		header := strings.TrimSpace(r.Header.Get("Authorization"))
		const prefix = "Bearer "
		if len(header) <= len(prefix) || !strings.EqualFold(header[:len(prefix)], prefix) {
			unauthorized(w)
			return
		}
		raw := strings.TrimSpace(header[len(prefix):])
		if raw == "" {
			unauthorized(w)
			return
		}
		user, err := s.store.UserByAccessToken(r.Context(), auth.HashToken(raw), s.now())
		if err != nil {
			if errors.Is(err, store.ErrNotFound) {
				unauthorized(w)
				return
			}
			writeError(w, http.StatusInternalServerError, "internal", "internal error")
			return
		}
		next(w, r, user)
	})
}

func (s *Server) clientIP(r *http.Request) string {
	if s.trustProxy {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			if i := strings.IndexByte(xff, ','); i >= 0 {
				xff = xff[:i]
			}
			if ip := strings.TrimSpace(xff); ip != "" {
				return ip
			}
		}
	}
	if host, _, err := net.SplitHostPort(r.RemoteAddr); err == nil {
		return host
	}
	return r.RemoteAddr
}

// ---------------------------------------------------------------- auth handlers

type loginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

type refreshRequest struct {
	RefreshToken string `json:"refreshToken"`
}

type changePasswordRequest struct {
	OldPassword string `json:"oldPassword"`
	NewPassword string `json:"newPassword"`
}

type userDTO struct {
	ID       string `json:"id"`
	Username string `json:"username"`
}

type tokenResponse struct {
	AccessToken  string  `json:"accessToken"`
	RefreshToken string  `json:"refreshToken"`
	ExpiresIn    int64   `json:"expiresIn"`
	User         userDTO `json:"user"`
}

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	var req loginRequest
	if !decodeJSON(w, r, &req, 1<<20) {
		return
	}
	username := strings.TrimSpace(req.Username)
	now := s.now()
	ipKey := "ip:" + s.clientIP(r)
	if ok, retry := s.ipLimiter.allow(ipKey, now); !ok {
		tooMany(w, retry)
		return
	}
	userKey := "user:" + strings.ToLower(username)
	if username != "" {
		if ok, retry := s.userLimiter.allow(userKey, now); !ok {
			tooMany(w, retry)
			return
		}
	}
	if !auth.ValidUsername(username) || !auth.ValidPassword(req.Password) {
		// A wrong format and a wrong password look the same from outside.
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "invalid username or password")
		return
	}

	user, err := s.store.UserByUsername(r.Context(), username)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			// Spend the same Argon2 work as a real check so timing does not reveal which
			// usernames exist.
			_, _ = s.hasher.Verify(s.dummyHash, req.Password)
			writeError(w, http.StatusUnauthorized, "invalid_credentials", "invalid username or password")
			return
		}
		writeError(w, http.StatusInternalServerError, "internal", "internal error")
		return
	}
	ok, err := s.hasher.Verify(user.PasswordHash, req.Password)
	if err != nil {
		s.log.Error("stored password hash is unreadable", "user", user.ID)
		writeError(w, http.StatusInternalServerError, "internal", "internal error")
		return
	}
	if !ok || user.Disabled {
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "invalid username or password")
		return
	}
	s.userLimiter.reset(userKey)
	access, refresh, err := s.issueTokens(r.Context(), user.ID, now)
	if err != nil {
		s.log.Error("issue tokens", "user", user.ID, "error", err)
		writeError(w, http.StatusInternalServerError, "internal", "internal error")
		return
	}
	writeJSON(w, http.StatusOK, tokenResponse{
		AccessToken:  access,
		RefreshToken: refresh,
		ExpiresIn:    int64(s.accessTTL.Seconds()),
		User:         userDTO{ID: user.ID, Username: user.Username},
	})
}

func (s *Server) refresh(w http.ResponseWriter, r *http.Request) {
	var req refreshRequest
	if !decodeJSON(w, r, &req, 1<<20) {
		return
	}
	if req.RefreshToken == "" {
		unauthorized(w)
		return
	}
	now := s.now()
	newRefresh, newHash, err := auth.NewToken()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "internal error")
		return
	}
	rt, err := s.store.RotateRefresh(r.Context(), auth.HashToken(req.RefreshToken), newHash, now.Add(s.refreshTTL), now)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) || errors.Is(err, store.ErrTokenReused) {
			unauthorized(w)
			return
		}
		s.log.Error("rotate refresh token", "error", err)
		writeError(w, http.StatusInternalServerError, "internal", "internal error")
		return
	}
	user, err := s.store.UserByID(r.Context(), rt.UserID)
	if err != nil || user.Disabled {
		// The account was disabled between the rotation and this read; end every session.
		_ = s.store.RevokeUserTokens(r.Context(), rt.UserID, now)
		unauthorized(w)
		return
	}
	access, accessHash, err := auth.NewToken()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "internal error")
		return
	}
	if err := s.store.CreateAccessToken(r.Context(), user.ID, accessHash, now.Add(s.accessTTL), now); err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "internal error")
		return
	}
	writeJSON(w, http.StatusOK, tokenResponse{
		AccessToken:  access,
		RefreshToken: newRefresh,
		ExpiresIn:    int64(s.accessTTL.Seconds()),
		User:         userDTO{ID: user.ID, Username: user.Username},
	})
}

// changePassword lets a signed-in user replace their own password after proving the old one.
// Every other session ends; this device gets a brand-new token pair in the response so the
// user does not have to sign in again with what they just typed.
func (s *Server) changePassword(w http.ResponseWriter, r *http.Request, user store.User) {
	var req changePasswordRequest
	if !decodeJSON(w, r, &req, 1<<20) {
		return
	}
	now := s.now()
	key := "passwd:" + user.ID
	if ok, retry := s.passwordLimiter.allow(key, now); !ok {
		tooMany(w, retry)
		return
	}
	if !auth.ValidPassword(req.NewPassword) {
		writeError(w, http.StatusBadRequest, "invalid_password", "new password must be 8-256 bytes")
		return
	}
	ok, err := s.hasher.Verify(user.PasswordHash, req.OldPassword)
	if err != nil {
		s.log.Error("stored password hash is unreadable", "user", user.ID)
		writeError(w, http.StatusInternalServerError, "internal", "internal error")
		return
	}
	if !ok {
		writeError(w, http.StatusUnauthorized, "invalid_old_password", "old password is incorrect")
		return
	}
	// Checked after the old password: telling someone who mistyped both fields that the two
	// match would be less useful than "the old password is wrong".
	if req.NewPassword == req.OldPassword {
		writeError(w, http.StatusBadRequest, "same_password", "new password must differ from the old one")
		return
	}
	hash, err := s.hasher.Hash(req.NewPassword)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "internal error")
		return
	}
	if err := s.store.SetPassword(r.Context(), user.ID, hash); err != nil {
		s.log.Error("set password", "user", user.ID, "error", err)
		writeError(w, http.StatusInternalServerError, "internal", "internal error")
		return
	}
	// A password change ends every open session; otherwise a token from before the change
	// keeps working. The pair issued below is the one exception, for this device.
	if err := s.store.RevokeUserTokens(r.Context(), user.ID, now); err != nil {
		s.log.Error("revoke user tokens", "user", user.ID, "error", err)
		writeError(w, http.StatusInternalServerError, "internal", "internal error")
		return
	}
	s.passwordLimiter.reset(key)
	access, refresh, err := s.issueTokens(r.Context(), user.ID, now)
	if err != nil {
		s.log.Error("issue tokens after password change", "user", user.ID, "error", err)
		writeError(w, http.StatusInternalServerError, "internal", "internal error")
		return
	}
	writeJSON(w, http.StatusOK, tokenResponse{
		AccessToken:  access,
		RefreshToken: refresh,
		ExpiresIn:    int64(s.accessTTL.Seconds()),
		User:         userDTO{ID: user.ID, Username: user.Username},
	})
}

func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	var req refreshRequest
	if !decodeJSON(w, r, &req, 1<<20) {
		return
	}
	if req.RefreshToken != "" {
		if err := s.store.RevokeFamilyByToken(r.Context(), auth.HashToken(req.RefreshToken), s.now()); err != nil {
			writeError(w, http.StatusInternalServerError, "internal", "internal error")
			return
		}
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) me(w http.ResponseWriter, _ *http.Request, user store.User) {
	writeJSON(w, http.StatusOK, userDTO{ID: user.ID, Username: user.Username})
}

func (s *Server) issueTokens(ctx context.Context, userID string, now time.Time) (string, string, error) {
	access, accessHash, err := auth.NewToken()
	if err != nil {
		return "", "", err
	}
	refresh, refreshHash, err := auth.NewToken()
	if err != nil {
		return "", "", err
	}
	family, err := auth.NewID()
	if err != nil {
		return "", "", err
	}
	if err := s.store.CreateAccessToken(ctx, userID, accessHash, now.Add(s.accessTTL), now); err != nil {
		return "", "", err
	}
	if err := s.store.CreateRefreshToken(ctx, userID, family, refreshHash, now.Add(s.refreshTTL), now); err != nil {
		return "", "", err
	}
	return access, refresh, nil
}

// ---------------------------------------------------------------- sync handlers

type blobMetaResponse struct {
	Revision      int64  `json:"revision"`
	ContentHash   string `json:"contentHash"`
	FormatVersion int    `json:"formatVersion"`
	SchemaVersion int    `json:"schemaVersion"`
	Size          int64  `json:"size"`
	UpdatedAt     int64  `json:"updatedAt"`
}

type revisionsResponse struct {
	Revisions []blobMetaResponse `json:"revisions"`
}

type putResponse struct {
	Revision int64 `json:"revision"`
}

func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	if err := s.store.Ping(r.Context()); err != nil {
		writeError(w, http.StatusServiceUnavailable, "unavailable", "database unavailable")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok", "version": s.version})
}

func (s *Server) syncMeta(w http.ResponseWriter, r *http.Request, user store.User) {
	meta, err := s.store.Meta(r.Context(), user.ID)
	if errors.Is(err, store.ErrNotFound) {
		writeJSON(w, http.StatusOK, blobMetaResponse{})
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "internal error")
		return
	}
	writeJSON(w, http.StatusOK, toMetaResponse(meta))
}

func (s *Server) getSync(w http.ResponseWriter, r *http.Request, user store.User) {
	blob, err := s.store.GetBlob(r.Context(), user.ID)
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "not_found", "no snapshot yet")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "internal error")
		return
	}
	setBlobHeaders(w, blob)
	etag := etagOf(blob.Revision)
	if inm := strings.TrimSpace(r.Header.Get("If-None-Match")); inm == etag || inm == "W/"+etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Content-Length", strconv.Itoa(len(blob.Data)))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(blob.Data)
}

func (s *Server) putSync(w http.ResponseWriter, r *http.Request, user store.User) {
	base, ok := parseIfMatch(r.Header.Get("If-Match"))
	if !ok {
		writeError(w, http.StatusBadRequest, "bad_request", "If-Match must be one non-negative revision")
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, s.maxBlob))
	if err != nil {
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			writeError(w, http.StatusRequestEntityTooLarge, "too_large", "snapshot is too large")
			return
		}
		writeError(w, http.StatusBadRequest, "bad_request", "could not read body")
		return
	}
	if len(body) == 0 {
		writeError(w, http.StatusBadRequest, "bad_request", "empty body")
		return
	}
	var head struct {
		FormatVersion int `json:"formatVersion"`
		SchemaVersion int `json:"schemaVersion"`
	}
	if err := json.Unmarshal(body, &head); err != nil {
		writeError(w, http.StatusBadRequest, "bad_request", "body is not a JSON object")
		return
	}
	if head.FormatVersion < 1 || head.FormatVersion > 1000 {
		writeError(w, http.StatusBadRequest, "bad_request", "unsupported formatVersion")
		return
	}
	if head.SchemaVersion < 1 || head.SchemaVersion > 10000 {
		writeError(w, http.StatusBadRequest, "bad_request", "unsupported schemaVersion")
		return
	}
	contentHash := strings.ToLower(strings.TrimSpace(r.Header.Get("X-Content-Hash")))
	if contentHash == "" {
		sum := sha256.Sum256(body)
		contentHash = hex.EncodeToString(sum[:])
	} else if !isHex64(contentHash) {
		writeError(w, http.StatusBadRequest, "bad_request", "X-Content-Hash must be 64 hex characters")
		return
	}
	now := s.now()
	revision, err := s.store.PutBlob(r.Context(), user.ID, base, store.Blob{
		BlobMeta: store.BlobMeta{
			ContentHash:   contentHash,
			FormatVersion: head.FormatVersion,
			SchemaVersion: head.SchemaVersion,
			Size:          int64(len(body)),
		},
		Data: body,
	}, now)
	if errors.Is(err, store.ErrConflict) {
		conflict := conflictEnvelope{
			Error: apiError{Code: "conflict", Message: "snapshot changed on the server"},
		}
		if meta, metaErr := s.store.Meta(r.Context(), user.ID); metaErr == nil {
			conflict.Revision = meta.Revision
			w.Header().Set("ETag", etagOf(meta.Revision))
		}
		writeJSON(w, http.StatusConflict, conflict)
		return
	}
	if err != nil {
		s.log.Error("store snapshot", "user", user.ID, "error", err)
		writeError(w, http.StatusInternalServerError, "internal", "internal error")
		return
	}
	w.Header().Set("X-Revision", strconv.FormatInt(revision, 10))
	w.Header().Set("ETag", etagOf(revision))
	writeJSON(w, http.StatusOK, putResponse{Revision: revision})
}

func (s *Server) listRevisions(w http.ResponseWriter, r *http.Request, user store.User) {
	limit := 50
	if raw := r.URL.Query().Get("limit"); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 1 {
			writeError(w, http.StatusBadRequest, "bad_request", "limit must be a positive integer")
			return
		}
		if n < limit {
			limit = n
		}
	}
	list, err := s.store.ListRevisions(r.Context(), user.ID, limit)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "internal error")
		return
	}
	out := make([]blobMetaResponse, 0, len(list))
	for _, m := range list {
		out = append(out, toMetaResponse(m))
	}
	writeJSON(w, http.StatusOK, revisionsResponse{Revisions: out})
}

func (s *Server) getRevision(w http.ResponseWriter, r *http.Request, user store.User) {
	raw := r.PathValue("revision")
	revision, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || revision < 1 {
		writeError(w, http.StatusBadRequest, "bad_request", "revision must be a positive integer")
		return
	}
	blob, err := s.store.GetRevision(r.Context(), user.ID, revision)
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "not_found", "revision not found")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "internal error")
		return
	}
	setBlobHeaders(w, blob)
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Content-Length", strconv.Itoa(len(blob.Data)))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(blob.Data)
}

// ---------------------------------------------------------------- responses

type apiError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type errorEnvelope struct {
	Error apiError `json:"error"`
}

type conflictEnvelope struct {
	Error    apiError `json:"error"`
	Revision int64    `json:"revision"`
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, errorEnvelope{Error: apiError{Code: code, Message: message}})
}

func unauthorized(w http.ResponseWriter) {
	w.Header().Set("WWW-Authenticate", `Bearer realm="asset-board"`)
	writeError(w, http.StatusUnauthorized, "unauthorized", "authentication required")
}

func tooMany(w http.ResponseWriter, retry time.Duration) {
	secs := int(math.Ceil(retry.Seconds()))
	if secs < 1 {
		secs = 1
	}
	w.Header().Set("Retry-After", strconv.Itoa(secs))
	writeError(w, http.StatusTooManyRequests, "rate_limited", "too many attempts")
}

func decodeJSON(w http.ResponseWriter, r *http.Request, dst any, limit int64) bool {
	r.Body = http.MaxBytesReader(w, r.Body, limit)
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(dst); err != nil {
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			writeError(w, http.StatusRequestEntityTooLarge, "too_large", "request body is too large")
			return false
		}
		writeError(w, http.StatusBadRequest, "bad_request", "body is not valid JSON")
		return false
	}
	if err := dec.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, "bad_request", "body must contain exactly one JSON value")
		return false
	}
	return true
}

func toMetaResponse(m store.BlobMeta) blobMetaResponse {
	return blobMetaResponse{
		Revision:      m.Revision,
		ContentHash:   m.ContentHash,
		FormatVersion: m.FormatVersion,
		SchemaVersion: m.SchemaVersion,
		Size:          m.Size,
		UpdatedAt:     m.UpdatedAt.Unix(),
	}
}

func etagOf(revision int64) string { return `"` + strconv.FormatInt(revision, 10) + `"` }

func setBlobHeaders(w http.ResponseWriter, blob store.Blob) {
	w.Header().Set("ETag", etagOf(blob.Revision))
	w.Header().Set("X-Revision", strconv.FormatInt(blob.Revision, 10))
	w.Header().Set("X-Content-Hash", blob.ContentHash)
	w.Header().Set("X-Format-Version", strconv.Itoa(blob.FormatVersion))
	w.Header().Set("X-Schema-Version", strconv.Itoa(blob.SchemaVersion))
	w.Header().Set("X-Updated-At", strconv.FormatInt(blob.UpdatedAt.Unix(), 10))
}

func parseIfMatch(raw string) (int64, bool) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return 0, true
	}
	if strings.HasPrefix(raw, "W/") || strings.Contains(raw, ",") {
		return 0, false
	}
	raw = strings.TrimPrefix(raw, `"`)
	raw = strings.TrimSuffix(raw, `"`)
	n, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || n < 0 {
		return 0, false
	}
	return n, true
}

func isHex64(s string) bool {
	if len(s) != 64 {
		return false
	}
	for _, c := range s {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return false
		}
	}
	return true
}

// ---------------------------------------------------------------- rate limiting

type rateEntry struct {
	start time.Time
	count int
}

// rateLimiter is a fixed-window counter. One instance per server process is enough: there is
// only one instance, and a restart resetting the counters is harmless.
type rateLimiter struct {
	mu        sync.Mutex
	window    time.Duration
	limit     int
	entries   map[string]rateEntry
	lastSweep time.Time
}

func newRateLimiter(limit int, window time.Duration) *rateLimiter {
	return &rateLimiter{window: window, limit: limit, entries: make(map[string]rateEntry)}
}

func (l *rateLimiter) allow(key string, now time.Time) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.lastSweep.IsZero() || now.Sub(l.lastSweep) >= l.window {
		for k, e := range l.entries {
			if now.Sub(e.start) >= l.window {
				delete(l.entries, k)
			}
		}
		l.lastSweep = now
	}
	e, ok := l.entries[key]
	if !ok || now.Sub(e.start) >= l.window {
		l.entries[key] = rateEntry{start: now, count: 1}
		return true, 0
	}
	if e.count >= l.limit {
		return false, e.start.Add(l.window).Sub(now)
	}
	e.count++
	l.entries[key] = e
	return true, 0
}

func (l *rateLimiter) reset(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.entries, key)
}
