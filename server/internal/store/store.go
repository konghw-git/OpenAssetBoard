// Package store is the server's SQLite persistence: users, tokens and one snapshot per user.
// Every query that touches user data takes the user id from the caller's authenticated
// context; nothing in this package reads an owner id from a request body.
package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/konghw-git/OpenAssetBoard/server/internal/auth"
	_ "modernc.org/sqlite"
)

const schemaVersion = 1

var (
	ErrNotFound          = errors.New("not found")
	ErrConflict          = errors.New("revision conflict")
	ErrDuplicateUsername = errors.New("username already exists")
	ErrTokenReused       = errors.New("refresh token reused")
)

type Options struct {
	// History is how many versions (including the current one) are kept per user.
	History int
}

type Store struct {
	db      *sql.DB
	history int
}

type User struct {
	ID           string
	Username     string
	PasswordHash string
	Disabled     bool
	CreatedAt    time.Time
}

type RefreshToken struct {
	UserID    string
	FamilyID  string
	ExpiresAt time.Time
}

type BlobMeta struct {
	Revision      int64
	ContentHash   string
	FormatVersion int
	SchemaVersion int
	Size          int64
	UpdatedAt     time.Time
}

type Blob struct {
	BlobMeta
	Data []byte
}

var schema = []string{
	`CREATE TABLE IF NOT EXISTS users (
		id TEXT PRIMARY KEY,
		username TEXT NOT NULL UNIQUE COLLATE NOCASE,
		password_hash TEXT NOT NULL,
		disabled INTEGER NOT NULL DEFAULT 0,
		created_at INTEGER NOT NULL
	)`,
	`CREATE TABLE IF NOT EXISTS access_tokens (
		token_hash BLOB PRIMARY KEY,
		user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
		expires_at INTEGER NOT NULL,
		created_at INTEGER NOT NULL
	)`,
	`CREATE INDEX IF NOT EXISTS access_tokens_expires ON access_tokens(expires_at)`,
	`CREATE INDEX IF NOT EXISTS access_tokens_user ON access_tokens(user_id)`,
	`CREATE TABLE IF NOT EXISTS refresh_tokens (
		token_hash BLOB PRIMARY KEY,
		user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
		family_id TEXT NOT NULL,
		expires_at INTEGER NOT NULL,
		created_at INTEGER NOT NULL,
		revoked_at INTEGER,
		replaced_by BLOB
	)`,
	`CREATE INDEX IF NOT EXISTS refresh_tokens_family ON refresh_tokens(family_id)`,
	`CREATE INDEX IF NOT EXISTS refresh_tokens_user ON refresh_tokens(user_id)`,
	`CREATE TABLE IF NOT EXISTS sync_blobs (
		user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
		revision INTEGER NOT NULL,
		content_hash TEXT NOT NULL,
		format_version INTEGER NOT NULL,
		schema_version INTEGER NOT NULL,
		size INTEGER NOT NULL,
		data BLOB NOT NULL,
		updated_at INTEGER NOT NULL
	)`,
	// Only older revisions live here; the current one is always in sync_blobs, so the
	// newest snapshot is never stored twice.
	`CREATE TABLE IF NOT EXISTS sync_history (
		user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
		revision INTEGER NOT NULL,
		content_hash TEXT NOT NULL,
		format_version INTEGER NOT NULL,
		schema_version INTEGER NOT NULL,
		size INTEGER NOT NULL,
		data BLOB NOT NULL,
		created_at INTEGER NOT NULL,
		PRIMARY KEY (user_id, revision)
	)`,
}

func Open(path string, opts Options) (*Store, error) {
	if opts.History <= 0 {
		opts.History = 10
	}
	// _txlock=immediate makes every write transaction take the write lock up front, so two
	// concurrent uploads queue behind each other instead of failing on a read-to-write upgrade.
	dsn := "file:" + path + "?_pragma=foreign_keys(1)&_pragma=busy_timeout(10000)" +
		"&_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)&_txlock=immediate"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open database: %w", err)
	}
	db.SetMaxOpenConns(4)
	db.SetMaxIdleConns(4)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := db.PingContext(ctx); err != nil {
		db.Close()
		return nil, fmt.Errorf("ping database: %w", err)
	}
	s := &Store{db: db, history: opts.History}
	if err := s.migrate(ctx); err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) migrate(ctx context.Context) error {
	var v int
	if err := s.db.QueryRowContext(ctx, "PRAGMA user_version").Scan(&v); err != nil {
		return fmt.Errorf("read schema version: %w", err)
	}
	if v > schemaVersion {
		return fmt.Errorf("database schema %d is newer than this server (%d)", v, schemaVersion)
	}
	if v == schemaVersion {
		return nil
	}
	for _, stmt := range schema {
		if _, err := s.db.ExecContext(ctx, stmt); err != nil {
			return fmt.Errorf("migrate: %w", err)
		}
	}
	if _, err := s.db.ExecContext(ctx, fmt.Sprintf("PRAGMA user_version = %d", schemaVersion)); err != nil {
		return fmt.Errorf("write schema version: %w", err)
	}
	return nil
}

func (s *Store) Close() error { return s.db.Close() }

func (s *Store) Ping(ctx context.Context) error { return s.db.PingContext(ctx) }

// BackupTo writes a consistent copy with VACUUM INTO. It refuses to overwrite an existing
// file: a backup command that silently replaces yesterday's copy is worse than failing.
func (s *Store) BackupTo(ctx context.Context, path string) error {
	if _, err := os.Stat(path); err == nil {
		return fmt.Errorf("refusing to overwrite existing file %s", path)
	}
	_, err := s.db.ExecContext(ctx, `VACUUM INTO ?`, path)
	return err
}

// ---------------------------------------------------------------- users

func (s *Store) CreateUser(ctx context.Context, username, passwordHash string, now time.Time) (User, error) {
	id, err := auth.NewID()
	if err != nil {
		return User{}, err
	}
	_, err = s.db.ExecContext(ctx,
		`INSERT INTO users(id, username, password_hash, disabled, created_at) VALUES(?,?,?,0,?)`,
		id, username, passwordHash, now.Unix())
	if err != nil {
		if isUniqueViolation(err) {
			return User{}, ErrDuplicateUsername
		}
		return User{}, err
	}
	return User{ID: id, Username: username, PasswordHash: passwordHash, CreatedAt: now.UTC()}, nil
}

func (s *Store) UserByUsername(ctx context.Context, username string) (User, error) {
	u, err := scanUser(s.db.QueryRowContext(ctx,
		`SELECT id, username, password_hash, disabled, created_at FROM users WHERE username = ?`, username))
	if errors.Is(err, sql.ErrNoRows) {
		return User{}, ErrNotFound
	}
	return u, err
}

func (s *Store) UserByID(ctx context.Context, id string) (User, error) {
	u, err := scanUser(s.db.QueryRowContext(ctx,
		`SELECT id, username, password_hash, disabled, created_at FROM users WHERE id = ?`, id))
	if errors.Is(err, sql.ErrNoRows) {
		return User{}, ErrNotFound
	}
	return u, err
}

func (s *Store) ListUsers(ctx context.Context) ([]User, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, username, password_hash, disabled, created_at FROM users ORDER BY username`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []User
	for rows.Next() {
		u, err := scanUser(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

func (s *Store) SetPassword(ctx context.Context, id, passwordHash string) error {
	return s.execOne(ctx, `UPDATE users SET password_hash = ? WHERE id = ?`, passwordHash, id)
}

func (s *Store) SetDisabled(ctx context.Context, id string, disabled bool) error {
	return s.execOne(ctx, `UPDATE users SET disabled = ? WHERE id = ?`, boolInt(disabled), id)
}

func (s *Store) execOne(ctx context.Context, query string, args ...any) error {
	res, err := s.db.ExecContext(ctx, query, args...)
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n == 0 {
		return ErrNotFound
	}
	return nil
}

// ---------------------------------------------------------------- access tokens

func (s *Store) CreateAccessToken(ctx context.Context, userID string, hash []byte, expiresAt, now time.Time) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO access_tokens(token_hash, user_id, expires_at, created_at) VALUES(?,?,?,?)`,
		hash, userID, expiresAt.Unix(), now.Unix())
	return err
}

func (s *Store) UserByAccessToken(ctx context.Context, hash []byte, now time.Time) (User, error) {
	u, err := scanUser(s.db.QueryRowContext(ctx, `
		SELECT u.id, u.username, u.password_hash, u.disabled, u.created_at
		FROM access_tokens t JOIN users u ON u.id = t.user_id
		WHERE t.token_hash = ? AND t.expires_at > ? AND u.disabled = 0`, hash, now.Unix()))
	if errors.Is(err, sql.ErrNoRows) {
		return User{}, ErrNotFound
	}
	return u, err
}

// ---------------------------------------------------------------- refresh tokens

func (s *Store) CreateRefreshToken(ctx context.Context, userID, familyID string, hash []byte, expiresAt, now time.Time) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO refresh_tokens(token_hash, user_id, family_id, expires_at, created_at) VALUES(?,?,?,?,?)`,
		hash, userID, familyID, expiresAt.Unix(), now.Unix())
	return err
}

// RotateRefresh consumes oldHash and issues newHash in the same family. Presenting a token
// that was already rotated revokes the whole family: that pattern means the token leaked.
func (s *Store) RotateRefresh(ctx context.Context, oldHash, newHash []byte, expiresAt, now time.Time) (RefreshToken, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return RefreshToken{}, err
	}
	defer tx.Rollback()

	var rt RefreshToken
	var exp int64
	var revoked sql.NullInt64
	err = tx.QueryRowContext(ctx,
		`SELECT user_id, family_id, expires_at, revoked_at FROM refresh_tokens WHERE token_hash = ?`, oldHash).
		Scan(&rt.UserID, &rt.FamilyID, &exp, &revoked)
	if errors.Is(err, sql.ErrNoRows) {
		return RefreshToken{}, ErrNotFound
	}
	if err != nil {
		return RefreshToken{}, err
	}
	if revoked.Valid {
		if err := revokeFamilyTx(ctx, tx, rt.FamilyID, now); err != nil {
			return RefreshToken{}, err
		}
		if err := tx.Commit(); err != nil {
			return RefreshToken{}, err
		}
		return RefreshToken{}, ErrTokenReused
	}
	if exp <= now.Unix() {
		return RefreshToken{}, ErrNotFound
	}

	res, err := tx.ExecContext(ctx,
		`UPDATE refresh_tokens SET revoked_at = ?, replaced_by = ? WHERE token_hash = ? AND revoked_at IS NULL`,
		now.Unix(), newHash, oldHash)
	if err != nil {
		return RefreshToken{}, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return RefreshToken{}, err
	}
	if n == 0 {
		if err := revokeFamilyTx(ctx, tx, rt.FamilyID, now); err != nil {
			return RefreshToken{}, err
		}
		if err := tx.Commit(); err != nil {
			return RefreshToken{}, err
		}
		return RefreshToken{}, ErrTokenReused
	}
	if _, err := tx.ExecContext(ctx,
		`INSERT INTO refresh_tokens(token_hash, user_id, family_id, expires_at, created_at) VALUES(?,?,?,?,?)`,
		newHash, rt.UserID, rt.FamilyID, expiresAt.Unix(), now.Unix()); err != nil {
		return RefreshToken{}, err
	}
	if err := tx.Commit(); err != nil {
		return RefreshToken{}, err
	}
	rt.ExpiresAt = expiresAt.UTC()
	return rt, nil
}

// RevokeFamilyByToken signs out the family that token belongs to. Unknown tokens are a no-op
// so logout cannot be used to probe whether a token exists.
func (s *Store) RevokeFamilyByToken(ctx context.Context, hash []byte, now time.Time) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE refresh_tokens SET revoked_at = ?
		WHERE revoked_at IS NULL AND family_id = (SELECT family_id FROM refresh_tokens WHERE token_hash = ?)`,
		now.Unix(), hash)
	return err
}

func (s *Store) RevokeFamily(ctx context.Context, familyID string, now time.Time) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL`, now.Unix(), familyID)
	return err
}

// RevokeUserTokens ends every session a user has open, used when an admin disables the
// account or resets its password.
func (s *Store) RevokeUserTokens(ctx context.Context, userID string, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `DELETE FROM access_tokens WHERE user_id = ?`, userID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx,
		`UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL`, now.Unix(), userID); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) PurgeExpired(ctx context.Context, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `DELETE FROM access_tokens WHERE expires_at <= ?`, now.Unix()); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM refresh_tokens WHERE expires_at <= ?`, now.Unix()); err != nil {
		return err
	}
	// A revoked-but-unexpired token is kept so that a later reuse can still revoke its
	// family; after 30 days that protection is no longer useful.
	cutoff := now.Add(-30 * 24 * time.Hour).Unix()
	if _, err := tx.ExecContext(ctx,
		`DELETE FROM refresh_tokens WHERE revoked_at IS NOT NULL AND revoked_at <= ?`, cutoff); err != nil {
		return err
	}
	return tx.Commit()
}

// ---------------------------------------------------------------- snapshots

// PutBlob writes revision base+1. base is what the client last saw; 0 means "no snapshot
// yet". A mismatch is ErrConflict and nothing changes.
func (s *Store) PutBlob(ctx context.Context, userID string, base int64, b Blob, now time.Time) (int64, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()

	var current int64
	err = tx.QueryRowContext(ctx, `SELECT revision FROM sync_blobs WHERE user_id = ?`, userID).Scan(&current)
	var next int64
	switch {
	case errors.Is(err, sql.ErrNoRows):
		if base != 0 {
			return 0, ErrConflict
		}
		next = 1
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO sync_blobs(user_id, revision, content_hash, format_version, schema_version, size, data, updated_at)
			VALUES(?,?,?,?,?,?,?,?)`,
			userID, next, b.ContentHash, b.FormatVersion, b.SchemaVersion, int64(len(b.Data)), b.Data, now.Unix()); err != nil {
			if isUniqueViolation(err) {
				return 0, ErrConflict
			}
			return 0, err
		}
	case err != nil:
		return 0, err
	default:
		if base != current {
			return 0, ErrConflict
		}
		next = current + 1
		// The revision being replaced becomes history before the row is updated.
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO sync_history(user_id, revision, content_hash, format_version, schema_version, size, data, created_at)
			SELECT user_id, revision, content_hash, format_version, schema_version, size, data, ?
			FROM sync_blobs WHERE user_id = ?`, now.Unix(), userID); err != nil {
			if isUniqueViolation(err) {
				return 0, ErrConflict
			}
			return 0, err
		}
		res, err := tx.ExecContext(ctx, `
			UPDATE sync_blobs SET revision = ?, content_hash = ?, format_version = ?, schema_version = ?,
				size = ?, data = ?, updated_at = ?
			WHERE user_id = ? AND revision = ?`,
			next, b.ContentHash, b.FormatVersion, b.SchemaVersion, int64(len(b.Data)), b.Data, now.Unix(), userID, current)
		if err != nil {
			return 0, err
		}
		n, err := res.RowsAffected()
		if err != nil {
			return 0, err
		}
		if n == 0 {
			return 0, ErrConflict
		}
	}
	// Keep the newest s.history revisions in total: current plus history-1 previous.
	if _, err := tx.ExecContext(ctx,
		`DELETE FROM sync_history WHERE user_id = ? AND revision <= ?`, userID, next-int64(s.history)); err != nil {
		return 0, err
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	return next, nil
}

func (s *Store) Meta(ctx context.Context, userID string) (BlobMeta, error) {
	var m BlobMeta
	var updated int64
	err := s.db.QueryRowContext(ctx, `
		SELECT revision, content_hash, format_version, schema_version, size, updated_at
		FROM sync_blobs WHERE user_id = ?`, userID).
		Scan(&m.Revision, &m.ContentHash, &m.FormatVersion, &m.SchemaVersion, &m.Size, &updated)
	if errors.Is(err, sql.ErrNoRows) {
		return BlobMeta{}, ErrNotFound
	}
	if err != nil {
		return BlobMeta{}, err
	}
	m.UpdatedAt = time.Unix(updated, 0).UTC()
	return m, nil
}

func (s *Store) GetBlob(ctx context.Context, userID string) (Blob, error) {
	return s.getBlob(ctx, userID, -1)
}

func (s *Store) GetRevision(ctx context.Context, userID string, revision int64) (Blob, error) {
	return s.getBlob(ctx, userID, revision)
}

func (s *Store) getBlob(ctx context.Context, userID string, revision int64) (Blob, error) {
	var b Blob
	var updated, created int64
	query := `
		SELECT revision, content_hash, format_version, schema_version, size, data, updated_at
		FROM sync_blobs WHERE user_id = ?`
	args := []any{userID}
	if revision >= 0 {
		query += ` AND revision = ?`
		args = append(args, revision)
	}
	err := s.db.QueryRowContext(ctx, query, args...).
		Scan(&b.Revision, &b.ContentHash, &b.FormatVersion, &b.SchemaVersion, &b.Size, &b.Data, &updated)
	if errors.Is(err, sql.ErrNoRows) && revision >= 0 {
		err = s.db.QueryRowContext(ctx, `
			SELECT revision, content_hash, format_version, schema_version, size, data, created_at
			FROM sync_history WHERE user_id = ? AND revision = ?`, userID, revision).
			Scan(&b.Revision, &b.ContentHash, &b.FormatVersion, &b.SchemaVersion, &b.Size, &b.Data, &created)
		if err == nil {
			updated = created
		}
	}
	if errors.Is(err, sql.ErrNoRows) {
		return Blob{}, ErrNotFound
	}
	if err != nil {
		return Blob{}, err
	}
	b.UpdatedAt = time.Unix(updated, 0).UTC()
	return b, nil
}

func (s *Store) ListRevisions(ctx context.Context, userID string, limit int) ([]BlobMeta, error) {
	if limit <= 0 {
		limit = 50
	}
	rows, err := s.db.QueryContext(ctx, `
		SELECT revision, content_hash, format_version, schema_version, size, updated_at FROM (
			SELECT revision, content_hash, format_version, schema_version, size, updated_at
			FROM sync_blobs WHERE user_id = ?
			UNION ALL
			SELECT revision, content_hash, format_version, schema_version, size, created_at AS updated_at
			FROM sync_history WHERE user_id = ?
		) ORDER BY revision DESC LIMIT ?`, userID, userID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []BlobMeta
	for rows.Next() {
		var m BlobMeta
		var updated int64
		if err := rows.Scan(&m.Revision, &m.ContentHash, &m.FormatVersion, &m.SchemaVersion, &m.Size, &updated); err != nil {
			return nil, err
		}
		m.UpdatedAt = time.Unix(updated, 0).UTC()
		out = append(out, m)
	}
	return out, rows.Err()
}

// ---------------------------------------------------------------- helpers

type scanner interface {
	Scan(dest ...any) error
}

func scanUser(sc scanner) (User, error) {
	var u User
	var disabled int
	var created int64
	if err := sc.Scan(&u.ID, &u.Username, &u.PasswordHash, &disabled, &created); err != nil {
		return User{}, err
	}
	u.Disabled = disabled != 0
	u.CreatedAt = time.Unix(created, 0).UTC()
	return u, nil
}

func revokeFamilyTx(ctx context.Context, tx *sql.Tx, familyID string, now time.Time) error {
	_, err := tx.ExecContext(ctx,
		`UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL`, now.Unix(), familyID)
	return err
}

func isUniqueViolation(err error) bool {
	return err != nil && strings.Contains(strings.ToLower(err.Error()), "unique constraint failed")
}

func boolInt(v bool) int {
	if v {
		return 1
	}
	return 0
}
