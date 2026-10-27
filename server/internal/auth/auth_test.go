package auth

import "testing"

func testHasher() *Hasher {
	// Small parameters keep the test suite fast; Verify must still accept them because
	// the parameters are read back from the PHC string.
	return NewHasherWithParams(8*1024, 1, 1)
}

func TestHashAndVerify(t *testing.T) {
	h := testHasher()
	encoded, err := h.Hash("correct horse battery staple")
	if err != nil {
		t.Fatal(err)
	}
	ok, err := h.Verify(encoded, "correct horse battery staple")
	if err != nil || !ok {
		t.Fatalf("verify = %v, %v", ok, err)
	}
	ok, err = h.Verify(encoded, "wrong password")
	if err != nil || ok {
		t.Fatalf("wrong password verify = %v, %v", ok, err)
	}
}

func TestHashUsesRandomSalt(t *testing.T) {
	h := testHasher()
	a, err := h.Hash("same password")
	if err != nil {
		t.Fatal(err)
	}
	b, err := h.Hash("same password")
	if err != nil {
		t.Fatal(err)
	}
	if a == b {
		t.Fatal("two hashes of the same password are identical")
	}
}

func TestVerifyRejectsMalformedHash(t *testing.T) {
	h := testHasher()
	for _, encoded := range []string{"", "plain", "$argon2i$v=19$m=1,t=1,p=1$aa$bb", "$argon2id$v=19$m=x$aa$bb"} {
		if ok, err := h.Verify(encoded, "password"); ok || err == nil {
			t.Fatalf("Verify(%q) = %v, %v; want false and an error", encoded, ok, err)
		}
	}
}

func TestParametersAreReadFromHash(t *testing.T) {
	strong := NewHasherWithParams(16*1024, 2, 1)
	encoded, err := strong.Hash("a password")
	if err != nil {
		t.Fatal(err)
	}
	weak := testHasher()
	if ok, err := weak.Verify(encoded, "a password"); err != nil || !ok {
		t.Fatalf("verify with a different hasher = %v, %v", ok, err)
	}
}

func TestValidUsername(t *testing.T) {
	for _, s := range []string{"konghw", "a_b.c-d", "abc", "User123"} {
		if !ValidUsername(s) {
			t.Errorf("ValidUsername(%q) = false", s)
		}
	}
	for _, s := range []string{"", "ab", "with space", "-leading", "中文名字", "a/b", string(make([]byte, 40))} {
		if ValidUsername(s) {
			t.Errorf("ValidUsername(%q) = true", s)
		}
	}
}

func TestValidPassword(t *testing.T) {
	if !ValidPassword("12345678") {
		t.Fatal("eight bytes should be allowed")
	}
	if ValidPassword("1234567") {
		t.Fatal("seven bytes should be rejected")
	}
	if ValidPassword(string(make([]byte, 257))) {
		t.Fatal("257 bytes should be rejected")
	}
}

func TestNewTokenIsUniqueAndHashed(t *testing.T) {
	a, ah, err := NewToken()
	if err != nil {
		t.Fatal(err)
	}
	b, bh, err := NewToken()
	if err != nil {
		t.Fatal(err)
	}
	if a == b {
		t.Fatal("tokens repeat")
	}
	if len(ah) != 32 || len(bh) != 32 {
		t.Fatalf("hash lengths = %d, %d", len(ah), len(bh))
	}
	if string(ah) == a {
		t.Fatal("stored hash is the raw token")
	}
	if string(HashToken(a)) != string(ah) {
		t.Fatal("HashToken disagrees with NewToken")
	}
}
