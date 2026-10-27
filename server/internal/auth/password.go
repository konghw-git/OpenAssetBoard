// Package auth holds password hashing and opaque token helpers. Tokens are random bytes,
// never JWTs: the server can revoke any of them by deleting a row.
package auth

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"regexp"
	"strings"

	"golang.org/x/crypto/argon2"
)

const (
	// 32 MiB / 3 passes is the OWASP "second choice" Argon2id profile: enough to make
	// offline guessing expensive, small enough that two concurrent logins fit on a 3 GB VPS.
	DefaultMemory      uint32 = 32 * 1024
	DefaultIterations  uint32 = 3
	DefaultParallelism uint8  = 1
	DefaultSaltLen            = 16
	DefaultKeyLen      uint32 = 32

	maxConcurrentHashes = 2
)

var (
	// Usernames are shown back to the user and used in CLI commands; keep them boring.
	usernameRE = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{2,31}$`)
	errBadHash = errors.New("malformed password hash")
)

// Hasher serialises Argon2id work so a burst of login attempts cannot allocate an
// unbounded amount of memory.
type Hasher struct {
	Memory      uint32
	Iterations  uint32
	Parallelism uint8
	SaltLen     int
	KeyLen      uint32
	sem         chan struct{}
}

func NewHasher() *Hasher {
	return NewHasherWithParams(DefaultMemory, DefaultIterations, DefaultParallelism)
}

func NewHasherWithParams(memory, iterations uint32, parallelism uint8) *Hasher {
	if memory == 0 {
		memory = DefaultMemory
	}
	if iterations == 0 {
		iterations = DefaultIterations
	}
	if parallelism == 0 {
		parallelism = DefaultParallelism
	}
	return &Hasher{
		Memory:      memory,
		Iterations:  iterations,
		Parallelism: parallelism,
		SaltLen:     DefaultSaltLen,
		KeyLen:      DefaultKeyLen,
		sem:         make(chan struct{}, maxConcurrentHashes),
	}
}

func (h *Hasher) acquire() func() {
	if h.sem == nil {
		return func() {}
	}
	h.sem <- struct{}{}
	return func() { <-h.sem }
}

// Hash returns the standard PHC string, so parameters travel with the hash and can be
// raised later without invalidating existing passwords.
func (h *Hasher) Hash(password string) (string, error) {
	salt := make([]byte, h.SaltLen)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	release := h.acquire()
	defer release()
	key := argon2.IDKey([]byte(password), salt, h.Iterations, h.Memory, h.Parallelism, h.KeyLen)
	b64 := base64.RawStdEncoding
	return fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s",
		argon2.Version, h.Memory, h.Iterations, h.Parallelism,
		b64.EncodeToString(salt), b64.EncodeToString(key)), nil
}

// Verify reads the parameters from encoded rather than from h, so a hash written by an
// older configuration still verifies.
func (h *Hasher) Verify(encoded, password string) (bool, error) {
	parts := strings.Split(encoded, "$")
	if len(parts) != 6 || parts[0] != "" || parts[1] != "argon2id" {
		return false, errBadHash
	}
	var version int
	if _, err := fmt.Sscanf(parts[2], "v=%d", &version); err != nil || version != argon2.Version {
		return false, errBadHash
	}
	var memory, iterations uint32
	var parallelism uint8
	if _, err := fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &memory, &iterations, &parallelism); err != nil {
		return false, errBadHash
	}
	if memory == 0 || iterations == 0 || parallelism == 0 {
		return false, errBadHash
	}
	b64 := base64.RawStdEncoding
	salt, err := b64.DecodeString(parts[4])
	if err != nil {
		return false, errBadHash
	}
	want, err := b64.DecodeString(parts[5])
	if err != nil || len(want) == 0 {
		return false, errBadHash
	}
	release := h.acquire()
	defer release()
	got := argon2.IDKey([]byte(password), salt, iterations, memory, parallelism, uint32(len(want)))
	return subtle.ConstantTimeCompare(got, want) == 1, nil
}

func ValidUsername(s string) bool { return usernameRE.MatchString(s) }

// ValidPassword counts bytes, not runes: the server stores a hash, so any UTF-8 is fine.
func ValidPassword(s string) bool { return len(s) >= 8 && len(s) <= 256 }
