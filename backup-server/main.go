// SPDX-License-Identifier: AGPL-3.0-or-later
// One local service: SQLite owns identities/quota; immutable files own ciphertext.
package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode/utf8"

	"golang.org/x/crypto/argon2"
	"golang.org/x/term"
	_ "modernc.org/sqlite"
)

const wire int64 = 1 << 20
const plainLimit int64 = 8 << 30
const cipherLimit int64 = plainLimit + (plainLimit/262144)*20 + 4106
const maxChunks = 8193
const safety int64 = 32 << 20
const pendingSeconds int64 = 7 * 86400
const leaseSeconds int64 = 900
const leaseMax int64 = 86400

type httpError struct {
	status  int
	message string
}

func (e httpError) Error() string       { return e.message }
func reject(status int, message string) { panic(httpError{status, message}) }
func must(err error) {
	if err != nil {
		panic(err)
	}
}
func id() string {
	b := make([]byte, 16)
	_, err := rand.Read(b)
	must(err)
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	h := hex.EncodeToString(b)
	return h[:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:]
}
func validID(s string) bool {
	if len(s) != 36 || s[8] != '-' || s[13] != '-' || s[18] != '-' || s[23] != '-' {
		return false
	}
	_, err := hex.DecodeString(strings.ReplaceAll(s, "-", ""))
	return err == nil && s == strings.ToLower(s)
}
func hash(b []byte) string { v := sha256.Sum256(b); return hex.EncodeToString(v[:]) }
func encoded(v any) []byte { b, e := json.Marshal(v); must(e); return b }
func jsonOut(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	must(json.NewEncoder(w).Encode(v))
}
func decode(r *http.Request, v any) {
	d := json.NewDecoder(r.Body)
	d.DisallowUnknownFields()
	if d.Decode(v) != nil {
		reject(422, "Invalid payload")
	}
	if d.Decode(new(any)) != io.EOF {
		reject(422, "Trailing payload")
	}
}

const schema = `
CREATE TABLE identity(id TEXT PRIMARY KEY);
CREATE TABLE users(id TEXT PRIMARY KEY,name TEXT UNIQUE NOT NULL,password TEXT NOT NULL);
CREATE TABLE sessions(token TEXT PRIMARY KEY,user TEXT NOT NULL REFERENCES users(id),device TEXT NOT NULL,expires INTEGER NOT NULL,revoked INTEGER NOT NULL DEFAULT 0);
CREATE TABLE attempts(name TEXT NOT NULL,at INTEGER NOT NULL);
CREATE TABLE libraries(id TEXT PRIMARY KEY,owner TEXT NOT NULL REFERENCES users(id));
CREATE TABLE operations(library TEXT NOT NULL REFERENCES libraries(id),id TEXT NOT NULL,kind TEXT NOT NULL,digest TEXT NOT NULL,manifest TEXT NOT NULL,state TEXT NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,cipher_bytes INTEGER NOT NULL,reserved_bytes INTEGER NOT NULL,target TEXT NOT NULL DEFAULT '',verified_chunks INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(library,id));
CREATE INDEX reservations ON operations(reserved_bytes,library);
CREATE TABLE chunks(library TEXT NOT NULL,operation TEXT NOT NULL,ordinal INTEGER NOT NULL,bytes INTEGER NOT NULL,hash TEXT NOT NULL,PRIMARY KEY(library,operation,ordinal),FOREIGN KEY(library,operation) REFERENCES operations(library,id));
CREATE TABLE leases(id TEXT PRIMARY KEY,library TEXT NOT NULL,operation TEXT NOT NULL,user TEXT NOT NULL,device TEXT NOT NULL,digest TEXT NOT NULL,expires INTEGER NOT NULL,deadline INTEGER NOT NULL,FOREIGN KEY(library,operation) REFERENCES operations(library,id));
CREATE INDEX leased ON leases(library,operation,expires);
PRAGMA user_version=2003;`

type service struct {
	db                          *sql.DB
	root, server                string
	accountLimit, globalLimit   int64
	requests, passwords, writes chan struct{}
	reaping                     sync.Mutex
	now                         func() int64
	fault                       func(string)
	publishBudget               time.Duration
}

// Context-aware writer admission avoids tying up cancelled HTTP requests in a mutex wait.
func (s *service) write(ctx context.Context, f func(*sql.Conn)) {
	select {
	case s.writes <- struct{}{}:
	case <-ctx.Done():
		panic(ctx.Err())
	}
	defer func() { <-s.writes }()
	c, err := s.db.Conn(ctx)
	must(err)
	defer c.Close()
	_, err = c.ExecContext(ctx, "BEGIN IMMEDIATE")
	must(err)
	defer c.ExecContext(context.Background(), "ROLLBACK")
	f(c)
	_, err = c.ExecContext(ctx, "COMMIT")
	must(err)
}
func exec(c *sql.Conn, ctx context.Context, q string, args ...any) {
	_, err := c.ExecContext(ctx, q, args...)
	must(err)
}
func scalar(c *sql.Conn, ctx context.Context, q string, args ...any) int64 {
	var n int64
	must(c.QueryRowContext(ctx, q, args...).Scan(&n))
	return n
}

func openService(path string, account, global int64) (*service, error) {
	if account <= 0 || global < account {
		return nil, errors.New("Invalid quotas")
	}
	absolute, err := filepath.Abs(path)
	if err != nil {
		return nil, err
	}
	if err = os.MkdirAll(filepath.Dir(absolute), 0700); err != nil {
		return nil, err
	}
	u := url.URL{Scheme: "file", Path: filepath.ToSlash(absolute)}
	if !strings.HasPrefix(u.Path, "/") {
		u.Path = "/" + u.Path
	}
	q := u.Query()
	q.Add("_pragma", "foreign_keys(1)")
	q.Add("_pragma", "busy_timeout(5000)")
	q.Add("_pragma", "synchronous(FULL)")
	u.RawQuery = q.Encode()
	db, err := sql.Open("sqlite", u.String())
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(4)
	s := &service{db: db, root: absolute + ".v2-chunks", accountLimit: account, globalLimit: global, requests: make(chan struct{}, 32), passwords: make(chan struct{}, 2), writes: make(chan struct{}, 1), now: func() int64 { return time.Now().Unix() }, fault: func(string) {}}
	var version int
	err = db.QueryRow("PRAGMA user_version").Scan(&version)
	if err != nil {
		db.Close()
		return nil, err
	}
	if version != 0 && version != 2003 {
		db.Close()
		return nil, errors.New("Unsupported schema; use a new v2 data directory, retain the old one")
	}
	_, err = db.Exec("PRAGMA journal_mode=WAL")
	if err != nil {
		db.Close()
		return nil, err
	}
	if version == 0 {
		tx, e := db.Begin()
		if e != nil {
			db.Close()
			return nil, e
		}
		_, e = tx.Exec(schema)
		if e == nil {
			_, e = tx.Exec("INSERT INTO identity VALUES(?)", id())
		}
		if e == nil {
			e = tx.Commit()
		} else {
			tx.Rollback()
		}
		if e != nil {
			db.Close()
			return nil, e
		}
	}
	if err = db.QueryRow("SELECT id FROM identity").Scan(&s.server); err != nil {
		db.Close()
		return nil, err
	}
	if err = os.MkdirAll(s.root, 0700); err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}

// Fixed Argon2id parameters bound memory and avoid trusting stored cost fields.
func passwordHash(password string) string {
	salt := make([]byte, 16)
	_, err := rand.Read(salt)
	must(err)
	key := argon2.IDKey([]byte(password), salt, 3, 64*1024, 1, 32)
	return "argon2id-v1$" + base64.RawStdEncoding.EncodeToString(salt) + "$" + base64.RawStdEncoding.EncodeToString(key)
}
func passwordOK(password, stored string) bool {
	parts := strings.Split(stored, "$")
	if len(parts) != 3 || parts[0] != "argon2id-v1" {
		return false
	}
	salt, e := base64.RawStdEncoding.DecodeString(parts[1])
	if e != nil || len(salt) != 16 {
		return false
	}
	key, e := base64.RawStdEncoding.DecodeString(parts[2])
	if e != nil || len(key) != 32 {
		return false
	}
	actual := argon2.IDKey([]byte(password), salt, 3, 64*1024, 1, 32)
	return subtle.ConstantTimeCompare(actual, key) == 1
}
func (s *service) addUser(name, password string) error {
	if !utf8.ValidString(name) || utf8.RuneCountInString(name) < 1 || utf8.RuneCountInString(name) > 100 || len(password) < 12 || len(password) > 1024 {
		return errors.New("Name and password (12–1024 bytes) required")
	}
	_, err := s.db.Exec("INSERT INTO users VALUES(?,?,?)", id(), name, passwordHash(password))
	return err
}

type session struct{ User, Device, Token string }

func (s *service) auth(r *http.Request) session {
	h := strings.Fields(r.Header.Get("Authorization"))
	if len(h) != 2 || !strings.EqualFold(h[0], "Bearer") || len(h[1]) > 128 {
		reject(401, "Session required")
	}
	u := session{Token: hash([]byte(h[1]))}
	e := s.db.QueryRowContext(r.Context(), "SELECT user,device FROM sessions WHERE token=? AND revoked=0 AND expires>?", u.Token, s.now()).Scan(&u.User, &u.Device)
	if e == sql.ErrNoRows {
		reject(401, "Session expired or revoked")
	}
	must(e)
	return u
}
func owner(c *sql.Conn, ctx context.Context, library string, u session) {
	var user string
	e := c.QueryRowContext(ctx, "SELECT owner FROM libraries WHERE id=?", library).Scan(&user)
	if e == sql.ErrNoRows || user != u.User {
		reject(404, "Library unavailable")
	}
	must(e)
}

func (s *service) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health/live", func(w http.ResponseWriter, r *http.Request) { jsonOut(w, map[string]bool{"alive": true}) })
	mux.HandleFunc("GET /health/ready", func(w http.ResponseWriter, r *http.Request) {
		s.write(r.Context(), func(c *sql.Conn) { exec(c, r.Context(), "UPDATE identity SET id=id") })
		jsonOut(w, map[string]any{"writable": true, "schema": 2003})
	})
	for _, protocol := range []int{1, 2} {
		p := protocol
		mux.HandleFunc(fmt.Sprintf("GET /v%d/identity", p), func(w http.ResponseWriter, r *http.Request) {
			v := map[string]any{"server": s.server, "issuer": "urn:inkweft:server:" + s.server, "protocol": 2, "authenticationProtocol": 1}
			if p == 2 {
				v["maxPlainBytes"] = plainLimit
				v["maxCipherBytes"] = cipherLimit
				v["wireBlock"] = wire
				v["maxChunks"] = maxChunks
				v["features"] = []string{"resume-upload", "resume-download", "download-lease"}
			}
			jsonOut(w, v)
		})
	}
	mux.HandleFunc("POST /v1/sessions", s.login)
	mux.HandleFunc("DELETE /v1/sessions/current", func(w http.ResponseWriter, r *http.Request) {
		u := s.auth(r)
		s.write(r.Context(), func(c *sql.Conn) { exec(c, r.Context(), "UPDATE sessions SET revoked=1 WHERE token=?", u.Token) })
		jsonOut(w, map[string]bool{"revoked": true})
	})
	mux.HandleFunc("DELETE /v1/devices/{device}", func(w http.ResponseWriter, r *http.Request) {
		u := s.auth(r)
		device := r.PathValue("device")
		if !validID(device) {
			reject(422, "Device ID")
		}
		s.write(r.Context(), func(c *sql.Conn) {
			exec(c, r.Context(), "UPDATE sessions SET revoked=1 WHERE user=? AND device=?", u.User, device)
		})
		jsonOut(w, map[string]bool{"revoked": true})
	})
	s.backupRoutes(mux)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		correlation := id()
		w.Header().Set("X-Request-ID", correlation)
		select {
		case s.requests <- struct{}{}:
			defer func() { <-s.requests }()
		default:
			w.Header().Set("Retry-After", "2")
			http.Error(w, "Busy", 429)
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
		defer cancel()
		r = r.WithContext(ctx)
		r.Body = http.MaxBytesReader(w, r.Body, wire+32768)
		defer func() {
			if e := recover(); e != nil {
				status := 507
				message := "Storage unavailable"
				switch v := e.(type) {
				case httpError:
					status = v.status
					message = v.message
				case *http.MaxBytesError:
					status = 413
					message = "Payload too large"
				}
				if status == 429 {
					w.Header().Set("Retry-After", "2")
				}
				http.Error(w, message, status)
				log.Printf("request=%s phase=http status=%d", correlation, status)
			}
		}()
		mux.ServeHTTP(w, r)
	})
}
func (s *service) login(w http.ResponseWriter, r *http.Request) {
	var in struct{ Username, Password, Device string }
	decode(r, &in)
	if !validID(in.Device) || !utf8.ValidString(in.Username) || utf8.RuneCountInString(in.Username) < 1 || utf8.RuneCountInString(in.Username) > 100 || len(in.Password) < 1 || len(in.Password) > 1024 {
		reject(422, "Login payload")
	}
	select {
	case s.passwords <- struct{}{}:
		defer func() { <-s.passwords }()
	default:
		reject(429, "Try later")
	}
	var user, stored string
	s.write(r.Context(), func(c *sql.Conn) {
		exec(c, r.Context(), "DELETE FROM attempts WHERE at<?", s.now()-60)
		if scalar(c, r.Context(), "SELECT COUNT(*) FROM attempts WHERE name=?", in.Username) >= 5 {
			reject(429, "Try later")
		}
		exec(c, r.Context(), "INSERT INTO attempts VALUES(?,?)", in.Username, s.now())
		e := c.QueryRowContext(r.Context(), "SELECT id,password FROM users WHERE name=?", in.Username).Scan(&user, &stored)
		if e != sql.ErrNoRows {
			must(e)
		}
	})
	if stored == "" { // Same KDF cost for an unknown account; never log account/credentials.
		argon2.IDKey([]byte(in.Password), make([]byte, 16), 3, 64*1024, 1, 32)
		reject(401, "Login failed")
	}
	if !passwordOK(in.Password, stored) {
		reject(401, "Login failed")
	}
	tokenBytes := make([]byte, 32)
	_, err := rand.Read(tokenBytes)
	must(err)
	token := base64.RawURLEncoding.EncodeToString(tokenBytes)
	expires := s.now() + 86400
	s.write(r.Context(), func(c *sql.Conn) {
		exec(c, r.Context(), "DELETE FROM attempts WHERE name=?", in.Username)
		exec(c, r.Context(), "INSERT INTO sessions VALUES(?,?,?,?,0)", hash([]byte(token)), user, in.Device, expires)
	})
	jsonOut(w, map[string]any{"token": token, "user": user, "device": in.Device, "server": s.server, "issuer": "urn:inkweft:server:" + s.server, "expires": expires})
}

func envQuota(name string, defaultValue int64) int64 {
	if value := os.Getenv(name); value != "" {
		n, e := strconv.ParseInt(value, 10, 64)
		if e != nil || n <= 0 {
			log.Fatal("Invalid " + name)
		}
		return n
	}
	return defaultValue
}
func main() {
	dbPath := flag.String("db", "state/backup-v2.db", "SQLite metadata path (v2 only)")
	host := flag.String("host", "127.0.0.1", "Listen interface")
	port := flag.Int("port", 18751, "Listen port")
	add := flag.String("add-user", "", "Create account, password on terminal/stdin")
	reset := flag.String("reset-password", "", "Reset account authentication and revoke sessions")
	fixture := flag.Bool("fixture", false, "Synthetic acceptance users; refuses populated database")
	crash := flag.String("test-crash", "", "Acceptance-only crash checkpoint, requires --test-mode")
	testMode := flag.Bool("test-mode", false, "Enable acceptance fault injection")
	publicationBudget := flag.Duration("test-publication-budget", 2*time.Second, "Acceptance-only publication slice, requires --test-mode")
	cert := flag.String("tls-cert", "", "TLS certificate path")
	key := flag.String("tls-key", "", "TLS key path")
	check := flag.String("check-ready", "", "Readiness URL and exit")
	flag.Parse()
	if (*cert == "") != (*key == "") {
		log.Fatal("TLS requires both certificate and key")
	}
	if *check != "" {
		client := &http.Client{Timeout: 3 * time.Second}
		r, e := client.Get(*check)
		if e != nil {
			log.Fatal("Not ready")
		}
		defer r.Body.Close()
		if r.StatusCode != 200 {
			log.Fatal("Not ready")
		}
		return
	}
	s, e := openService(*dbPath, envQuota("INKWEFT_BACKUP_ACCOUNT_BYTES", 32<<30), envQuota("INKWEFT_BACKUP_GLOBAL_BYTES", 128<<30))
	if e != nil {
		log.Fatal(e)
	}
	defer s.db.Close()
	s.publishBudget = *publicationBudget
	if *publicationBudget <= 0 || *publicationBudget > 2*time.Second || (*publicationBudget != 2*time.Second && !*testMode) {
		log.Fatal("Invalid publication test budget")
	}
	if *add != "" || *reset != "" {
		fmt.Fprint(os.Stderr, "Password (12+ bytes): ")
		var secret []byte
		if term.IsTerminal(int(os.Stdin.Fd())) {
			secret, e = term.ReadPassword(int(os.Stdin.Fd()))
			fmt.Fprintln(os.Stderr)
		} else {
			secret, e = io.ReadAll(io.LimitReader(os.Stdin, 1025))
			secret = []byte(strings.TrimRight(string(secret), "\r\n"))
		}
		if e != nil || len(secret) < 12 || len(secret) > 1024 {
			log.Fatal("Invalid password")
		}
		if *add != "" {
			e = s.addUser(*add, string(secret))
		} else {
			hashed := passwordHash(string(secret))
			s.write(context.Background(), func(c *sql.Conn) {
				var user string
				must(c.QueryRowContext(context.Background(), "SELECT id FROM users WHERE name=?", *reset).Scan(&user))
				exec(c, context.Background(), "UPDATE users SET password=? WHERE id=?", hashed, user)
				exec(c, context.Background(), "UPDATE sessions SET revoked=1 WHERE user=?", user)
			})
		}
		clear(secret)
		if e != nil {
			log.Fatal("Account update failed")
		}
		fmt.Println("Authentication saved; recovery key remains separate")
		return
	}
	if *fixture {
		var n int
		must(s.db.QueryRow("SELECT COUNT(*) FROM users").Scan(&n))
		if n != 0 {
			log.Fatal("Fixture refuses a populated database")
		}
		must(s.addUser("synthetic-alice", "synthetic-alice-password-123"))
		must(s.addUser("synthetic-bob", "synthetic-bob-password-456"))
	}
	if *crash != "" {
		if !*testMode {
			log.Fatal("Fault injection requires test mode")
		}
		s.fault = func(phase string) {
			if phase == *crash {
				os.Exit(92)
			}
		}
	}
	s.reap(context.Background())
	server := &http.Server{Addr: fmt.Sprintf("%s:%d", *host, *port), Handler: s.handler(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 35 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 << 10}
	stopped := make(chan os.Signal, 1)
	signal.Notify(stopped, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-stopped
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		server.Shutdown(ctx)
	}()
	// Reap idle uploads/deferred deletions without requiring a later user request.
	go func() {
		ticker := time.NewTicker(time.Minute)
		defer ticker.Stop()
		for range ticker.C {
			func() {
				defer func() {
					if recover() != nil {
						log.Print("phase=retirement status=507")
					}
				}()
				s.reap(context.Background())
			}()
		}
	}()
	log.Print("phase=service state=ready protocol=2")
	if *cert != "" && *key != "" {
		e = server.ListenAndServeTLS(*cert, *key)
	} else {
		e = server.ListenAndServe()
	}
	if e != nil && !errors.Is(e, http.ErrServerClosed) {
		log.Fatal("Service stopped")
	}
}
