package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/konghw-git/OpenAssetBoard/server/internal/auth"
	"github.com/konghw-git/OpenAssetBoard/server/internal/store"
)

type testClock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *testClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

func (c *testClock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
}

type testEnv struct {
	t      *testing.T
	store  *store.Store
	server *Server
	http   *httptest.Server
	clock  *testClock
	hasher *auth.Hasher
}

func newTestEnv(t *testing.T, configure func(*Config)) *testEnv {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "test.db"), store.Options{History: 10})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	clock := &testClock{t: time.Date(2026, 10, 27, 12, 0, 0, 0, time.UTC)}
	hasher := auth.NewHasherWithParams(8*1024, 1, 1)
	cfg := Config{
		Store:          st,
		Hasher:         hasher,
		AccessTTL:      15 * time.Minute,
		RefreshTTL:     30 * 24 * time.Hour,
		MaxBlobBytes:   1 << 20,
		Now:            clock.Now,
		Logger:         slog.New(slog.NewTextHandler(io.Discard, nil)),
		Version:        "test",
		LoginWindow:    5 * time.Minute,
		LoginIPLimit:   5,
		LoginUserLimit: 5,
	}
	if configure != nil {
		configure(&cfg)
	}
	server, err := New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	hs := httptest.NewServer(server.Handler())
	t.Cleanup(hs.Close)
	return &testEnv{t: t, store: st, server: server, http: hs, clock: clock, hasher: hasher}
}

type response struct {
	status int
	header http.Header
	body   []byte
}

func (e *testEnv) do(method, path string, body []byte, headers map[string]string) response {
	e.t.Helper()
	req, err := http.NewRequest(method, e.http.URL+path, bytes.NewReader(body))
	if err != nil {
		e.t.Fatal(err)
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := e.http.Client().Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		e.t.Fatal(err)
	}
	return response{status: resp.StatusCode, header: resp.Header, body: b}
}

func (e *testEnv) createUser(username, password string) store.User {
	e.t.Helper()
	hash, err := e.hasher.Hash(password)
	if err != nil {
		e.t.Fatal(err)
	}
	user, err := e.store.CreateUser(context.Background(), username, hash, e.clock.Now())
	if err != nil {
		e.t.Fatal(err)
	}
	return user
}

func (e *testEnv) tryLogin(username, password string) response {
	e.t.Helper()
	body, _ := json.Marshal(loginRequest{Username: username, Password: password})
	return e.do(http.MethodPost, "/v1/auth/login", body, map[string]string{"Content-Type": "application/json"})
}

func (e *testEnv) login(username, password string) (access, refresh string) {
	e.t.Helper()
	r := e.tryLogin(username, password)
	if r.status != http.StatusOK {
		e.t.Fatalf("login %s: status %d body %s", username, r.status, r.body)
	}
	var tr tokenResponse
	decodeBody(e.t, r, &tr)
	if tr.AccessToken == "" || tr.RefreshToken == "" {
		e.t.Fatalf("login returned empty tokens: %s", r.body)
	}
	return tr.AccessToken, tr.RefreshToken
}

func (e *testEnv) put(access string, base int64, body string, contentHash string) response {
	e.t.Helper()
	headers := map[string]string{
		"Authorization": "Bearer " + access,
		"Content-Type":  "application/json",
	}
	if base >= 0 {
		headers["If-Match"] = strconv.FormatInt(base, 10)
	}
	if contentHash != "" {
		headers["X-Content-Hash"] = contentHash
	}
	return e.do(http.MethodPut, "/v1/sync", []byte(body), headers)
}

func (e *testEnv) get(access, path string, extra map[string]string) response {
	e.t.Helper()
	headers := map[string]string{"Authorization": "Bearer " + access}
	for k, v := range extra {
		headers[k] = v
	}
	return e.do(http.MethodGet, path, nil, headers)
}

func decodeBody(t *testing.T, r response, dst any) {
	t.Helper()
	if err := json.Unmarshal(r.body, dst); err != nil {
		t.Fatalf("decode %s: %v", r.body, err)
	}
}

func backupBody(marker string) string {
	return fmt.Sprintf(`{"formatVersion":14,"schemaVersion":13,"exportedAt":1,"marker":%q}`, marker)
}

// ---------------------------------------------------------------- tests

func TestHealthz(t *testing.T) {
	e := newTestEnv(t, nil)
	r := e.do(http.MethodGet, "/healthz", nil, nil)
	if r.status != http.StatusOK {
		t.Fatalf("status = %d", r.status)
	}
	var body map[string]string
	decodeBody(t, r, &body)
	if body["status"] != "ok" || body["version"] != "test" {
		t.Fatalf("body = %v", body)
	}
	if r.header.Get("X-Content-Type-Options") != "nosniff" || r.header.Get("Cache-Control") != "no-store" {
		t.Fatalf("security headers missing: %v", r.header)
	}
}

func TestLoginAndMe(t *testing.T) {
	e := newTestEnv(t, nil)
	user := e.createUser("alice", "password123")
	access, refresh := e.login("alice", "password123")
	if access == "" || refresh == "" {
		t.Fatal("empty tokens")
	}
	r := e.get(access, "/v1/me", nil)
	if r.status != http.StatusOK {
		t.Fatalf("me status = %d body %s", r.status, r.body)
	}
	var me userDTO
	decodeBody(t, r, &me)
	if me.ID != user.ID || me.Username != "alice" {
		t.Fatalf("me = %+v", me)
	}
}

func TestLoginRejectsBadCredentials(t *testing.T) {
	e := newTestEnv(t, nil)
	e.createUser("alice", "password123")
	for _, tc := range []struct{ user, pass string }{
		{"alice", "wrong-password"},
		{"nobody", "password123"},
		{"alice", "short"},
	} {
		if r := e.tryLogin(tc.user, tc.pass); r.status != http.StatusUnauthorized {
			t.Fatalf("login(%q,%q) status = %d body %s", tc.user, tc.pass, r.status, r.body)
		}
	}
}

func TestLoginIsRateLimited(t *testing.T) {
	e := newTestEnv(t, nil)
	e.createUser("bob", "password123")
	for i := 0; i < 5; i++ {
		if r := e.tryLogin("bob", "wrong"); r.status != http.StatusUnauthorized {
			t.Fatalf("attempt %d status = %d", i+1, r.status)
		}
	}
	r := e.tryLogin("bob", "wrong")
	if r.status != http.StatusTooManyRequests {
		t.Fatalf("sixth attempt status = %d, want 429", r.status)
	}
	if r.header.Get("Retry-After") == "" {
		t.Fatal("missing Retry-After")
	}
	// The correct password is locked out too until the window passes.
	if r := e.tryLogin("bob", "password123"); r.status != http.StatusTooManyRequests {
		t.Fatalf("correct password while limited = %d", r.status)
	}
	e.clock.Advance(6 * time.Minute)
	if r := e.tryLogin("bob", "password123"); r.status != http.StatusOK {
		t.Fatalf("after window = %d body %s", r.status, r.body)
	}
}

func TestAccessTokenExpires(t *testing.T) {
	e := newTestEnv(t, nil)
	e.createUser("alice", "password123")
	access, _ := e.login("alice", "password123")
	e.clock.Advance(16 * time.Minute)
	if r := e.get(access, "/v1/me", nil); r.status != http.StatusUnauthorized {
		t.Fatalf("expired token status = %d", r.status)
	}
}

func TestRefreshRotatesAndReuseRevokesFamily(t *testing.T) {
	e := newTestEnv(t, nil)
	e.createUser("alice", "password123")
	_, refresh1 := e.login("alice", "password123")

	body, _ := json.Marshal(refreshRequest{RefreshToken: refresh1})
	r := e.do(http.MethodPost, "/v1/auth/refresh", body, nil)
	if r.status != http.StatusOK {
		t.Fatalf("refresh status = %d body %s", r.status, r.body)
	}
	var tr tokenResponse
	decodeBody(t, r, &tr)
	refresh2 := tr.RefreshToken
	if refresh2 == refresh1 || tr.AccessToken == "" {
		t.Fatal("refresh did not rotate")
	}

	// Replaying the consumed token must fail and kill the family.
	if r := e.refreshWith(refresh1); r.status != http.StatusUnauthorized {
		t.Fatalf("replayed refresh status = %d", r.status)
	}
	if r := e.refreshWith(refresh2); r.status != http.StatusUnauthorized {
		t.Fatalf("token from the revoked family status = %d", r.status)
	}
}

func (e *testEnv) refreshWith(token string) response {
	e.t.Helper()
	body, _ := json.Marshal(refreshRequest{RefreshToken: token})
	return e.do(http.MethodPost, "/v1/auth/refresh", body, nil)
}

func TestLogoutRevokesFamily(t *testing.T) {
	e := newTestEnv(t, nil)
	e.createUser("alice", "password123")
	_, refresh := e.login("alice", "password123")
	body, _ := json.Marshal(refreshRequest{RefreshToken: refresh})
	if r := e.do(http.MethodPost, "/v1/auth/logout", body, nil); r.status != http.StatusNoContent {
		t.Fatalf("logout status = %d", r.status)
	}
	if r := e.refreshWith(refresh); r.status != http.StatusUnauthorized {
		t.Fatalf("refresh after logout = %d", r.status)
	}
	// Logging out an unknown token is a no-op, not an error that reveals it never existed.
	if r := e.do(http.MethodPost, "/v1/auth/logout", []byte(`{"refreshToken":"nope"}`), nil); r.status != http.StatusNoContent {
		t.Fatalf("logout unknown token = %d", r.status)
	}
}

func TestSyncIsolation(t *testing.T) {
	e := newTestEnv(t, nil)
	e.createUser("alice", "password123")
	e.createUser("bob", "password123")
	alice, _ := e.login("alice", "password123")
	bob, _ := e.login("bob", "password123")

	if r := e.put(alice, 0, backupBody("alice"), ""); r.status != http.StatusOK {
		t.Fatalf("alice put = %d %s", r.status, r.body)
	}
	if r := e.get(bob, "/v1/sync", nil); r.status != http.StatusNotFound {
		t.Fatalf("bob sees a snapshot before uploading: %d", r.status)
	}
	if r := e.put(bob, 0, backupBody("bob"), ""); r.status != http.StatusOK {
		t.Fatalf("bob put = %d %s", r.status, r.body)
	}
	if r := e.get(alice, "/v1/sync", nil); !strings.Contains(string(r.body), "alice") {
		t.Fatalf("alice got %s", r.body)
	}
	if r := e.get(bob, "/v1/sync", nil); !strings.Contains(string(r.body), "bob") {
		t.Fatalf("bob got %s", r.body)
	}
	// Revision numbers are per user; bob's revision 1 is his own, not alice's.
	if r := e.get(bob, "/v1/sync/revisions/1", nil); !strings.Contains(string(r.body), "bob") {
		t.Fatalf("bob revision 1 = %s", r.body)
	}
}

func TestSyncConflict(t *testing.T) {
	e := newTestEnv(t, nil)
	e.createUser("alice", "password123")
	access, _ := e.login("alice", "password123")

	if r := e.put(access, 0, backupBody("one"), ""); r.status != http.StatusOK {
		t.Fatalf("first put = %d %s", r.status, r.body)
	}
	r := e.put(access, 0, backupBody("stale"), "")
	if r.status != http.StatusConflict {
		t.Fatalf("stale put = %d body %s", r.status, r.body)
	}
	var conflict conflictEnvelope
	decodeBody(t, r, &conflict)
	if conflict.Revision != 1 || conflict.Error.Code != "conflict" {
		t.Fatalf("conflict = %+v", conflict)
	}
	if r := e.put(access, 1, backupBody("two"), ""); r.status != http.StatusOK {
		t.Fatalf("second put = %d %s", r.status, r.body)
	}
	if r := e.put(access, 1, backupBody("stale-again"), ""); r.status != http.StatusConflict {
		t.Fatalf("stale second put = %d", r.status)
	}
}

func TestConcurrentPutHasOneWinner(t *testing.T) {
	e := newTestEnv(t, nil)
	e.createUser("alice", "password123")
	access, _ := e.login("alice", "password123")
	if r := e.put(access, 0, backupBody("base"), ""); r.status != http.StatusOK {
		t.Fatalf("base put = %d", r.status)
	}

	start := make(chan struct{})
	results := make(chan int, 2)
	for i := 0; i < 2; i++ {
		go func(i int) {
			<-start
			results <- e.put(access, 1, backupBody(fmt.Sprintf("writer-%d", i)), "").status
		}(i)
	}
	close(start)
	a, b := <-results, <-results
	if !((a == http.StatusOK && b == http.StatusConflict) || (a == http.StatusConflict && b == http.StatusOK)) {
		t.Fatalf("concurrent statuses = %d, %d; want one 200 and one 409", a, b)
	}
	meta := e.meta(access)
	if meta.Revision != 2 {
		t.Fatalf("final revision = %d", meta.Revision)
	}
}

func TestPutWithoutIfMatchCannotOverwrite(t *testing.T) {
	e := newTestEnv(t, nil)
	e.createUser("alice", "password123")
	access, _ := e.login("alice", "password123")
	if r := e.put(access, 0, backupBody("one"), ""); r.status != http.StatusOK {
		t.Fatalf("first put = %d", r.status)
	}
	if r := e.put(access, -1, backupBody("overwrite"), ""); r.status != http.StatusConflict {
		t.Fatalf("put without If-Match = %d", r.status)
	}
}

func TestPutValidatesBody(t *testing.T) {
	e := newTestEnv(t, nil)
	e.createUser("alice", "password123")
	access, _ := e.login("alice", "password123")
	cases := []struct {
		name string
		body string
		hash string
	}{
		{"empty object", `{}`, ""},
		{"not json", `not json`, ""},
		{"zero format", `{"formatVersion":0,"schemaVersion":13}`, ""},
		{"bad hash", backupBody("x"), "abc"},
	}
	for _, tc := range cases {
		if r := e.put(access, 0, tc.body, tc.hash); r.status != http.StatusBadRequest {
			t.Fatalf("%s: status = %d body %s", tc.name, r.status, r.body)
		}
	}
	if r := e.do(http.MethodPut, "/v1/sync", nil, map[string]string{"Authorization": "Bearer " + access}); r.status != http.StatusBadRequest {
		t.Fatalf("empty body status = %d", r.status)
	}
}

func TestPutTooLarge(t *testing.T) {
	e := newTestEnv(t, func(c *Config) { c.MaxBlobBytes = 128 })
	e.createUser("alice", "password123")
	access, _ := e.login("alice", "password123")
	big := fmt.Sprintf(`{"formatVersion":14,"schemaVersion":13,"pad":%q}`, strings.Repeat("x", 256))
	if r := e.put(access, 0, big, ""); r.status != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversized put = %d body %s", r.status, r.body)
	}
}

func (e *testEnv) meta(access string) blobMetaResponse {
	e.t.Helper()
	r := e.get(access, "/v1/sync/meta", nil)
	if r.status != http.StatusOK {
		e.t.Fatalf("meta status = %d body %s", r.status, r.body)
	}
	var m blobMetaResponse
	decodeBody(e.t, r, &m)
	return m
}

func TestSyncMetaAndETag(t *testing.T) {
	e := newTestEnv(t, nil)
	e.createUser("alice", "password123")
	access, _ := e.login("alice", "password123")

	if m := e.meta(access); m.Revision != 0 {
		t.Fatalf("empty meta = %+v", m)
	}
	body := backupBody("one")
	if r := e.put(access, 0, body, ""); r.status != http.StatusOK {
		t.Fatalf("put = %d", r.status)
	}
	m := e.meta(access)
	if m.Revision != 1 || m.FormatVersion != 14 || m.SchemaVersion != 13 || m.Size != int64(len(body)) || len(m.ContentHash) != 64 {
		t.Fatalf("meta = %+v", m)
	}
	for _, inm := range []string{`"1"`, `W/"1"`} {
		r := e.get(access, "/v1/sync", map[string]string{"If-None-Match": inm})
		if r.status != http.StatusNotModified {
			t.Fatalf("If-None-Match %s = %d", inm, r.status)
		}
	}
	r := e.get(access, "/v1/sync", nil)
	if r.status != http.StatusOK || r.header.Get("X-Revision") != "1" || r.header.Get("ETag") != `"1"` {
		t.Fatalf("get sync status = %d headers = %v", r.status, r.header)
	}
	if string(r.body) != body {
		t.Fatalf("get sync body = %s", r.body)
	}
}

func TestContentHashHeaderIsStored(t *testing.T) {
	e := newTestEnv(t, nil)
	e.createUser("alice", "password123")
	access, _ := e.login("alice", "password123")
	hash := strings.Repeat("ab", 32)
	if r := e.put(access, 0, backupBody("one"), hash); r.status != http.StatusOK {
		t.Fatalf("put = %d", r.status)
	}
	if m := e.meta(access); m.ContentHash != hash {
		t.Fatalf("content hash = %q", m.ContentHash)
	}
}

func TestHistoryKeepsTenRevisions(t *testing.T) {
	e := newTestEnv(t, nil)
	e.createUser("alice", "password123")
	access, _ := e.login("alice", "password123")
	for i := int64(1); i <= 12; i++ {
		if r := e.put(access, i-1, backupBody(strconv.FormatInt(i, 10)), ""); r.status != http.StatusOK {
			t.Fatalf("put %d = %d %s", i, r.status, r.body)
		}
	}
	r := e.get(access, "/v1/sync/revisions", nil)
	if r.status != http.StatusOK {
		t.Fatalf("revisions status = %d", r.status)
	}
	var list revisionsResponse
	decodeBody(t, r, &list)
	if len(list.Revisions) != 10 {
		t.Fatalf("revisions = %d, want 10", len(list.Revisions))
	}
	if list.Revisions[0].Revision != 12 || list.Revisions[9].Revision != 3 {
		t.Fatalf("revision range = %d..%d", list.Revisions[0].Revision, list.Revisions[9].Revision)
	}
	if r := e.get(access, "/v1/sync/revisions/2", nil); r.status != http.StatusNotFound {
		t.Fatalf("pruned revision 2 = %d", r.status)
	}
	if r := e.get(access, "/v1/sync/revisions/3", nil); r.status != http.StatusOK || !strings.Contains(string(r.body), `"3"`) {
		t.Fatalf("revision 3 = %d %s", r.status, r.body)
	}
	if r := e.get(access, "/v1/sync/revisions/0", nil); r.status != http.StatusBadRequest {
		t.Fatalf("revision 0 = %d", r.status)
	}
	if r := e.get(access, "/v1/sync/revisions/nope", nil); r.status != http.StatusBadRequest {
		t.Fatalf("revision nope = %d", r.status)
	}
}

func TestDisabledUserLosesEverything(t *testing.T) {
	e := newTestEnv(t, nil)
	user := e.createUser("alice", "password123")
	access, refresh := e.login("alice", "password123")
	if err := e.store.SetDisabled(context.Background(), user.ID, true); err != nil {
		t.Fatal(err)
	}
	if r := e.get(access, "/v1/me", nil); r.status != http.StatusUnauthorized {
		t.Fatalf("me after disable = %d", r.status)
	}
	if r := e.refreshWith(refresh); r.status != http.StatusUnauthorized {
		t.Fatalf("refresh after disable = %d", r.status)
	}
	if r := e.tryLogin("alice", "password123"); r.status != http.StatusUnauthorized {
		t.Fatalf("login after disable = %d", r.status)
	}
}

func TestNoPublicRegistration(t *testing.T) {
	e := newTestEnv(t, nil)
	r := e.do(http.MethodPost, "/v1/auth/register", []byte(`{"username":"x","password":"password123"}`), nil)
	if r.status != http.StatusNotFound && r.status != http.StatusMethodNotAllowed {
		t.Fatalf("registration status = %d", r.status)
	}
}

func TestAuthIsRequired(t *testing.T) {
	e := newTestEnv(t, nil)
	for _, path := range []string{"/v1/me", "/v1/sync", "/v1/sync/meta", "/v1/sync/revisions"} {
		if r := e.do(http.MethodGet, path, nil, nil); r.status != http.StatusUnauthorized {
			t.Fatalf("%s without token = %d", path, r.status)
		}
	}
	if r := e.do(http.MethodGet, "/v1/me", nil, map[string]string{"Authorization": "Bearer nonsense"}); r.status != http.StatusUnauthorized {
		t.Fatalf("bad token = %d", r.status)
	}
}

func TestRefreshAndLogoutRequireARequestShape(t *testing.T) {
	e := newTestEnv(t, nil)
	if r := e.refreshWith(""); r.status != http.StatusUnauthorized {
		t.Fatalf("empty refresh = %d", r.status)
	}
	if r := e.do(http.MethodPost, "/v1/auth/refresh", []byte(`not json`), nil); r.status != http.StatusBadRequest {
		t.Fatalf("bad refresh json = %d", r.status)
	}
}
