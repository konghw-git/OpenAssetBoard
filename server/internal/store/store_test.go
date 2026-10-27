package store

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
	"time"
)

func testStore(t *testing.T) *Store {
	t.Helper()
	s, err := Open(filepath.Join(t.TempDir(), "test.db"), Options{History: 3})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func TestCreateUserRejectsDuplicateUsernameCaseInsensitively(t *testing.T) {
	s := testStore(t)
	ctx := context.Background()
	now := time.Now()
	if _, err := s.CreateUser(ctx, "Alice", "hash", now); err != nil {
		t.Fatal(err)
	}
	if _, err := s.CreateUser(ctx, "alice", "hash", now); !errors.Is(err, ErrDuplicateUsername) {
		t.Fatalf("second create = %v, want ErrDuplicateUsername", err)
	}
}

func TestExpiredRefreshTokenCannotRotate(t *testing.T) {
	s := testStore(t)
	ctx := context.Background()
	now := time.Now().UTC()
	user, err := s.CreateUser(ctx, "alice", "hash", now)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.CreateRefreshToken(ctx, user.ID, "family", []byte("old"), now.Add(-time.Minute), now.Add(-time.Hour)); err != nil {
		t.Fatal(err)
	}
	if _, err := s.RotateRefresh(ctx, []byte("old"), []byte("new"), now.Add(time.Hour), now); !errors.Is(err, ErrNotFound) {
		t.Fatalf("rotate expired = %v, want ErrNotFound", err)
	}
}

func TestPurgeExpiredRemovesOldTokens(t *testing.T) {
	s := testStore(t)
	ctx := context.Background()
	now := time.Now().UTC()
	user, err := s.CreateUser(ctx, "alice", "hash", now)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.CreateAccessToken(ctx, user.ID, []byte("a"), now.Add(-time.Minute), now.Add(-time.Hour)); err != nil {
		t.Fatal(err)
	}
	if err := s.CreateRefreshToken(ctx, user.ID, "f1", []byte("r1"), now.Add(-time.Minute), now.Add(-time.Hour)); err != nil {
		t.Fatal(err)
	}
	if err := s.CreateRefreshToken(ctx, user.ID, "f2", []byte("r2"), now.Add(time.Hour), now.Add(-time.Hour)); err != nil {
		t.Fatal(err)
	}
	if err := s.PurgeExpired(ctx, now); err != nil {
		t.Fatal(err)
	}
	var access, refresh int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM access_tokens`).Scan(&access); err != nil {
		t.Fatal(err)
	}
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM refresh_tokens`).Scan(&refresh); err != nil {
		t.Fatal(err)
	}
	if access != 0 || refresh != 1 {
		t.Fatalf("after purge: access=%d refresh=%d, want 0 and 1", access, refresh)
	}
}

func TestPutBlobRevisionAndHistory(t *testing.T) {
	s := testStore(t)
	ctx := context.Background()
	now := time.Now().UTC()
	user, err := s.CreateUser(ctx, "alice", "hash", now)
	if err != nil {
		t.Fatal(err)
	}
	blob := func(hash string) Blob {
		return Blob{BlobMeta: BlobMeta{ContentHash: hash, FormatVersion: 14, SchemaVersion: 13}, Data: []byte(hash)}
	}
	if rev, err := s.PutBlob(ctx, user.ID, 0, blob("a"), now); err != nil || rev != 1 {
		t.Fatalf("put a = %d, %v", rev, err)
	}
	if _, err := s.PutBlob(ctx, user.ID, 0, blob("stale"), now); !errors.Is(err, ErrConflict) {
		t.Fatalf("stale put = %v", err)
	}
	if rev, err := s.PutBlob(ctx, user.ID, 1, blob("b"), now); err != nil || rev != 2 {
		t.Fatalf("put b = %d, %v", rev, err)
	}
	if rev, err := s.PutBlob(ctx, user.ID, 2, blob("c"), now); err != nil || rev != 3 {
		t.Fatalf("put c = %d, %v", rev, err)
	}
	// History is 3 in this test: current 3 plus revisions 1 and 2.
	if _, err := s.GetRevision(ctx, user.ID, 1); err != nil {
		t.Fatalf("revision 1 missing: %v", err)
	}
	if _, err := s.GetRevision(ctx, user.ID, 2); err != nil {
		t.Fatalf("revision 2 missing: %v", err)
	}
	if rev, err := s.PutBlob(ctx, user.ID, 3, blob("d"), now); err != nil || rev != 4 {
		t.Fatalf("put d = %d, %v", rev, err)
	}
	if _, err := s.GetRevision(ctx, user.ID, 1); !errors.Is(err, ErrNotFound) {
		t.Fatalf("revision 1 survived pruning: %v", err)
	}
	if _, err := s.GetRevision(ctx, user.ID, 2); err != nil {
		t.Fatalf("revision 2 should still exist: %v", err)
	}
}

func TestUsersAreIsolatedAtTheStoreLayer(t *testing.T) {
	s := testStore(t)
	ctx := context.Background()
	now := time.Now().UTC()
	a, _ := s.CreateUser(ctx, "alice", "hash", now)
	b, _ := s.CreateUser(ctx, "bob", "hash", now)
	if _, err := s.PutBlob(ctx, a.ID, 0, Blob{BlobMeta: BlobMeta{ContentHash: "a", FormatVersion: 14, SchemaVersion: 13}, Data: []byte("alice")}, now); err != nil {
		t.Fatal(err)
	}
	if _, err := s.GetBlob(ctx, b.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("bob can read a blob: %v", err)
	}
	if rev, err := s.PutBlob(ctx, b.ID, 0, Blob{BlobMeta: BlobMeta{ContentHash: "b", FormatVersion: 14, SchemaVersion: 13}, Data: []byte("bob")}, now); err != nil || rev != 1 {
		t.Fatalf("bob first put = %d, %v", rev, err)
	}
}
func TestListRevisionsReturnsNewestFirst(t *testing.T) {
	s := testStore(t)
	ctx := context.Background()
	now := time.Now().UTC()
	user, _ := s.CreateUser(ctx, "alice", "hash", now)
	blob := func(hash string) Blob {
		return Blob{BlobMeta: BlobMeta{ContentHash: hash, FormatVersion: 14, SchemaVersion: 13}, Data: []byte(hash)}
	}
	for i := int64(1); i <= 5; i++ {
		if _, err := s.PutBlob(ctx, user.ID, i-1, blob("x"), now); err != nil {
			t.Fatal(err)
		}
	}
	list, err := s.ListRevisions(ctx, user.ID, 50)
	if err != nil {
		t.Fatalf("ListRevisions: %v", err)
	}
	if len(list) != 3 {
		t.Fatalf("len = %d, want 3 (history option)", len(list))
	}
	if list[0].Revision != 5 || list[len(list)-1].Revision != 3 {
		t.Fatalf("revisions = %d..%d", list[0].Revision, list[len(list)-1].Revision)
	}
}

func TestBackupToProducesAReadableCopy(t *testing.T) {
	s := testStore(t)
	ctx := context.Background()
	now := time.Now().UTC()
	if _, err := s.CreateUser(ctx, "alice", "hash", now); err != nil {
		t.Fatal(err)
	}
	out := filepath.Join(t.TempDir(), "copy.db")
	if err := s.BackupTo(ctx, out); err != nil {
		t.Fatalf("BackupTo: %v", err)
	}
	copy, err := Open(out, Options{History: 3})
	if err != nil {
		t.Fatalf("open copy: %v", err)
	}
	defer copy.Close()
	if _, err := copy.UserByUsername(ctx, "alice"); err != nil {
		t.Fatalf("user missing from copy: %v", err)
	}
	if err := s.BackupTo(ctx, out); err == nil {
		t.Fatal("BackupTo overwrote an existing file")
	}
}
