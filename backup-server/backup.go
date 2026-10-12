// SPDX-License-Identifier: AGPL-3.0-or-later
package main

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type manifest struct {
	Bytes      int64    `json:"bytes"`
	Chunks     []string `json:"chunks"`
	Format     string   `json:"format"`
	PlainBytes int64    `json:"plainBytes"`
}

func (m manifest) validate() {
	framing := ((m.PlainBytes + 262143) / 262144) * 20
	if m.Format != "inkweft.encrypted-backup.v2" || m.PlainBytes < 1 || m.PlainBytes > plainLimit || m.Bytes <= m.PlainBytes+framing+10 || m.Bytes > m.PlainBytes+framing+4106 || len(m.Chunks) < 1 || len(m.Chunks) > maxChunks || m.Bytes <= (int64(len(m.Chunks))-1)*wire || m.Bytes > int64(len(m.Chunks))*wire {
		reject(422, "Invalid manifest")
	}
	for _, h := range m.Chunks {
		_, e := hex.DecodeString(h)
		if e != nil || len(h) != 64 || h != strings.ToLower(h) {
			reject(422, "Invalid chunk hash")
		}
	}
}

type operation struct {
	Library, ID, Kind, Digest, Manifest, State, Target string
	Created, Updated, Bytes, Reserved, Verified        int64
}

func (o operation) receipt() map[string]any {
	return map[string]any{"operation": o.ID, "library": o.Library, "kind": o.Kind, "digest": o.Digest, "state": o.State, "protocol": 2, "verified": o.Verified}
}
func lookup(c *sql.Conn, ctx context.Context, library, op string, u session) *operation {
	owner(c, ctx, library, u)
	v := &operation{}
	e := c.QueryRowContext(ctx, "SELECT library,id,kind,digest,manifest,state,created,updated,cipher_bytes,reserved_bytes,target,verified_chunks FROM operations WHERE library=? AND id=?", library, op).Scan(&v.Library, &v.ID, &v.Kind, &v.Digest, &v.Manifest, &v.State, &v.Created, &v.Updated, &v.Bytes, &v.Reserved, &v.Target, &v.Verified)
	if e == sql.ErrNoRows {
		return nil
	}
	must(e)
	return v
}
func requireOp(c *sql.Conn, ctx context.Context, library, op string, u session) *operation {
	v := lookup(c, ctx, library, op, u)
	if v == nil {
		reject(404, "Operation unavailable")
	}
	return v
}
func (s *service) folder(library, op string) string {
	if !validID(library) || !validID(op) {
		reject(422, "Invalid identity")
	}
	path := filepath.Join(s.root, library, op)
	rel, e := filepath.Rel(s.root, path)
	if e != nil || strings.HasPrefix(rel, "..") {
		reject(507, "Storage path unavailable")
	}
	return path
}
func (s *service) route(mux *http.ServeMux, pattern string, action func(http.ResponseWriter, *http.Request, session)) {
	mux.HandleFunc(pattern, func(w http.ResponseWriter, r *http.Request) {
		u := s.auth(r)
		for _, key := range []string{"library", "operation", "target", "lease"} {
			if v := r.PathValue(key); v != "" && !validID(v) {
				reject(422, "Invalid identity")
			}
		}
		action(w, r, u)
	})
}
func (s *service) read(ctx context.Context, f func(*sql.Conn)) {
	c, e := s.db.Conn(ctx)
	must(e)
	defer c.Close()
	f(c)
}
func reservations(c *sql.Conn, ctx context.Context, user string) (total, pending int64) {
	q := "SELECT COALESCE(SUM(o.reserved_bytes),0),COALESCE(SUM(o.state='PENDING'),0) FROM operations o JOIN libraries l ON l.id=o.library WHERE o.reserved_bytes>0"
	args := []any{}
	if user != "" {
		q += " AND l.owner=?"
		args = append(args, user)
	}
	must(c.QueryRowContext(ctx, q, args...).Scan(&total, &pending))
	return
}
func outstanding(c *sql.Conn, ctx context.Context) int64 {
	return scalar(c, ctx, "SELECT COALESCE(SUM(MAX(0,o.cipher_bytes-COALESCE((SELECT SUM(b.bytes) FROM chunks b WHERE b.library=o.library AND b.operation=o.id),0))),0) FROM operations o WHERE o.kind='UPLOAD' AND o.state='PENDING'")
}
func (s *service) backupRoutes(mux *http.ServeMux) {
	s.route(mux, "GET /v2/capacity", func(w http.ResponseWriter, r *http.Request, u session) {
		var v map[string]any
		s.read(r.Context(), func(c *sql.Conn) {
			own, pending := reservations(c, r.Context(), u.User)
			total, _ := reservations(c, r.Context(), "")
			free, e := freeBytes(s.root)
			must(e)
			available := min(s.accountLimit-own, s.globalLimit-total, free-outstanding(c, r.Context())-safety)
			v = map[string]any{"accountLimit": s.accountLimit, "globalLimit": s.globalLimit, "accountReserved": own, "globalReserved": total, "available": max(int64(0), available), "pending": pending, "maxPlainBytes": plainLimit, "maxCipherBytes": cipherLimit}
		})
		jsonOut(w, v)
	})
	s.route(mux, "PUT /v2/libraries/{library}", func(w http.ResponseWriter, r *http.Request, u session) {
		library := r.PathValue("library")
		s.write(r.Context(), func(c *sql.Conn) {
			if scalar(c, r.Context(), "SELECT COUNT(*) FROM libraries WHERE id=?", library) > 0 {
				owner(c, r.Context(), library, u)
			} else {
				if scalar(c, r.Context(), "SELECT COUNT(*) FROM libraries WHERE owner=?", u.User) >= 16 {
					reject(507, "Library quota reached")
				}
				exec(c, r.Context(), "INSERT INTO libraries VALUES(?,?)", library, u.User)
			}
		})
		jsonOut(w, map[string]string{"library": library})
	})
	s.route(mux, "PUT /v2/libraries/{library}/uploads/{operation}", s.begin)
	s.route(mux, "PUT /v2/libraries/{library}/uploads/{operation}/chunks/{ordinal}", s.put)
	s.route(mux, "POST /v2/libraries/{library}/uploads/{operation}/publish", s.publish)
	s.route(mux, "GET /v2/libraries/{library}/operations/{operation}", func(w http.ResponseWriter, r *http.Request, u session) {
		var v map[string]any
		s.read(r.Context(), func(c *sql.Conn) {
			v = requireOp(c, r.Context(), r.PathValue("library"), r.PathValue("operation"), u).receipt()
		})
		jsonOut(w, v)
	})
	s.route(mux, "GET /v2/libraries/{library}/uploads/{operation}", func(w http.ResponseWriter, r *http.Request, u session) {
		var v map[string]any
		s.read(r.Context(), func(c *sql.Conn) {
			o := requireOp(c, r.Context(), r.PathValue("library"), r.PathValue("operation"), u)
			if o.Kind != "UPLOAD" {
				reject(409, "Not an upload")
			}
			v = o.receipt()
			var m manifest
			must(json.Unmarshal([]byte(o.Manifest), &m))
			v["manifest"] = m
			rows, e := c.QueryContext(r.Context(), "SELECT ordinal FROM chunks WHERE library=? AND operation=? ORDER BY ordinal", o.Library, o.ID)
			must(e)
			defer rows.Close()
			received := []int{}
			for rows.Next() {
				var i int
				must(rows.Scan(&i))
				received = append(received, i)
			}
			must(rows.Err())
			v["received"] = received
		})
		jsonOut(w, v)
	})
	s.route(mux, "GET /v2/libraries/{library}/versions", func(w http.ResponseWriter, r *http.Request, u session) {
		versions := []map[string]any{}
		s.read(r.Context(), func(c *sql.Conn) {
			library := r.PathValue("library")
			owner(c, r.Context(), library, u)
			pending := r.URL.Query().Get("include_pending") == "true"
			rows, e := c.QueryContext(r.Context(), "SELECT id FROM operations WHERE library=? AND kind='UPLOAD' AND (state='PUBLISHED' OR (? AND state IN ('PENDING','DELETING'))) ORDER BY created DESC,id", library, pending)
			must(e)
			ids := []string{}
			for rows.Next() {
				var i string
				must(rows.Scan(&i))
				ids = append(ids, i)
			}
			must(rows.Err())
			rows.Close()
			for _, i := range ids {
				o := requireOp(c, r.Context(), library, i, u)
				v := o.receipt()
				v["created"] = o.Created
				v["bytes"] = o.Bytes
				v["format"] = "inkweft.encrypted-backup.v2"
				versions = append(versions, v)
			}
		})
		jsonOut(w, versions)
	})
	s.route(mux, "GET /v2/libraries/{library}/versions/{operation}", func(w http.ResponseWriter, r *http.Request, u session) {
		var m manifest
		s.read(r.Context(), func(c *sql.Conn) {
			o := requireOp(c, r.Context(), r.PathValue("library"), r.PathValue("operation"), u)
			if o.Kind != "UPLOAD" || o.State != "PUBLISHED" {
				reject(404, "Version unavailable")
			}
			must(json.Unmarshal([]byte(o.Manifest), &m))
		})
		jsonOut(w, m)
	})
	s.route(mux, "POST /v2/libraries/{library}/versions/{operation}/leases", s.acquire)
	s.route(mux, "PUT /v2/libraries/{library}/versions/{operation}/leases/{lease}", s.renew)
	s.route(mux, "DELETE /v2/libraries/{library}/versions/{operation}/leases/{lease}", func(w http.ResponseWriter, r *http.Request, u session) {
		s.write(r.Context(), func(c *sql.Conn) {
			requireOp(c, r.Context(), r.PathValue("library"), r.PathValue("operation"), u)
			exec(c, r.Context(), "DELETE FROM leases WHERE id=? AND library=? AND operation=? AND user=? AND device=?", r.PathValue("lease"), r.PathValue("library"), r.PathValue("operation"), u.User, u.Device)
		})
		s.reap(r.Context())
		jsonOut(w, map[string]bool{"released": true})
	})
	s.route(mux, "GET /v2/libraries/{library}/versions/{operation}/chunks/{ordinal}", s.download)
	s.route(mux, "DELETE /v2/libraries/{library}/versions/{target}", s.delete)
}
func (s *service) begin(w http.ResponseWriter, r *http.Request, u session) {
	var m manifest
	decode(r, &m)
	m.validate()
	library, op := r.PathValue("library"), r.PathValue("operation")
	payload := string(encoded(m))
	fingerprint := hash([]byte(payload))
	s.reap(r.Context())
	var result map[string]any
	s.write(r.Context(), func(c *sql.Conn) {
		old := lookup(c, r.Context(), library, op, u)
		if old != nil {
			if old.Kind != "UPLOAD" || old.Digest != fingerprint {
				reject(409, "Operation payload changed")
			}
			result = old.receipt()
			return
		}
		own, pending := reservations(c, r.Context(), u.User)
		total, _ := reservations(c, r.Context(), "")
		count := scalar(c, r.Context(), "SELECT COUNT(*) FROM operations WHERE library=? AND kind='UPLOAD' AND state IN ('PENDING','PUBLISHED','DELETING')", library)
		if count >= 10 || pending >= 4 || m.Bytes > s.accountLimit-own || m.Bytes > s.globalLimit-total {
			reject(507, "Quota reached")
		}
		free, e := freeBytes(s.root)
		must(e)
		if m.Bytes > free-outstanding(c, r.Context())-safety {
			reject(507, "Physical storage budget reached")
		}
		at := s.now()
		exec(c, r.Context(), "INSERT INTO operations(library,id,kind,digest,manifest,state,created,updated,cipher_bytes,reserved_bytes,target) VALUES(?,?,?,?,?,?,?,?,?,?,?)", library, op, "UPLOAD", fingerprint, payload, "PENDING", at, at, m.Bytes, m.Bytes, "")
		result = requireOp(c, r.Context(), library, op, u).receipt()
	})
	jsonOut(w, result)
}
func ordinal(r *http.Request, m manifest) int {
	n, e := strconv.Atoi(r.PathValue("ordinal"))
	if e != nil || n < 0 || n >= len(m.Chunks) {
		reject(422, "Chunk index")
	}
	return n
}
func (s *service) put(w http.ResponseWriter, r *http.Request, u session) {
	data, e := io.ReadAll(io.LimitReader(r.Body, wire+1))
	must(e)
	if int64(len(data)) > wire {
		reject(413, "Chunk too large")
	}
	library, op := r.PathValue("library"), r.PathValue("operation")
	n := 0
	s.write(r.Context(), func(c *sql.Conn) {
		o := requireOp(c, r.Context(), library, op, u)
		if o.Kind != "UPLOAD" || (o.State != "PENDING" && o.State != "PUBLISHED") {
			reject(409, "Upload unavailable")
		}
		var m manifest
		must(json.Unmarshal([]byte(o.Manifest), &m))
		n = ordinal(r, m)
		size := min(wire, m.Bytes-int64(n)*wire)
		fingerprint := hash(data)
		if int64(len(data)) != size || fingerprint != m.Chunks[n] {
			reject(422, "Chunk integrity")
		}
		folder := s.folder(library, op)
		must(os.MkdirAll(folder, 0700))
		// Preserve the root's new library entry as well as the operation directory.
		must(syncDir(s.root))
		target := filepath.Join(folder, fmtChunk(n))
		_, statError := os.Stat(target)
		if statError == nil {
			stream, e := os.Open(target)
			must(e)
			stored, e := io.ReadAll(io.LimitReader(stream, wire+1))
			stream.Close()
			must(e)
			if int64(len(stored)) != size || hash(stored) != fingerprint {
				reject(409, "Stored chunk damaged")
			}
		} else {
			if !os.IsNotExist(statError) {
				must(statError)
			}
			if o.State != "PENDING" {
				reject(409, "Published version damaged")
			}
			free, e := freeBytes(s.root)
			must(e)
			if free < size+safety {
				reject(507, "Storage full")
			}
			tmp := filepath.Join(folder, id()+".partial")
			f, e := os.OpenFile(tmp, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
			must(e)
			func() {
				defer os.Remove(tmp)
				defer f.Close()
				_, e = f.Write(data)
				must(e)
				must(f.Sync())
				must(f.Close())
				s.fault("before-file-publish")
				must(publishFile(tmp, target))
				must(syncDir(filepath.Dir(folder)))
				s.fault("after-file-publish")
			}()
		}
		if scalar(c, r.Context(), "SELECT COUNT(*) FROM chunks WHERE library=? AND operation=? AND ordinal=?", library, op, n) == 0 {
			if o.State != "PENDING" {
				reject(409, "Published metadata damaged")
			}
			exec(c, r.Context(), "INSERT INTO chunks VALUES(?,?,?,?,?)", library, op, n, size, fingerprint)
			exec(c, r.Context(), "UPDATE operations SET updated=? WHERE library=? AND id=?", s.now(), library, op)
		}
		s.fault("before-chunk-commit")
	})
	s.fault("after-chunk-commit")
	jsonOut(w, map[string]int{"stored": n})
}
func fmtChunk(n int) string { return strconv.FormatInt(int64(n)+0x100000000, 16)[1:] + ".chunk" }
func (s *service) publish(w http.ResponseWriter, r *http.Request, u session) {
	library, op := r.PathValue("library"), r.PathValue("operation")
	var m manifest
	var next int64
	// Disk integrity traversal holds no writer transaction; socket output and cancellation
	// never block another account behind an eight-GiB writer transaction.
	s.read(r.Context(), func(c *sql.Conn) {
		o := requireOp(c, r.Context(), library, op, u)
		if o.Kind != "UPLOAD" || (o.State != "PENDING" && o.State != "PUBLISHED") {
			reject(409, "Upload unavailable")
		}
		must(json.Unmarshal([]byte(o.Manifest), &m))
		if o.State == "PUBLISHED" {
			return
		}
		var count, total int64
		must(c.QueryRowContext(r.Context(), "SELECT COUNT(*),COALESCE(SUM(bytes),0) FROM chunks WHERE library=? AND operation=?", library, op).Scan(&count, &total))
		if count != int64(len(m.Chunks)) || total != m.Bytes {
			reject(409, "Upload incomplete")
		}
		next = o.Verified
		budget := s.publishBudget
		if budget == 0 {
			budget = 2 * time.Second
		}
		until := time.Now().Add(budget)
		buffer := make([]byte, 64*1024)
		digest := sha256.New()
		for i := int(next); i < len(m.Chunks); i++ {
			if r.Context().Err() != nil {
				panic(r.Context().Err())
			}
			file, e := os.Open(filepath.Join(s.folder(library, op), fmtChunk(i)))
			if e != nil {
				reject(409, "Upload incomplete")
			}
			digest.Reset()
			n, e := io.CopyBuffer(digest, io.LimitReader(file, wire+1), buffer)
			file.Close()
			if e != nil || n != min(wire, m.Bytes-int64(i)*wire) || hex.EncodeToString(digest.Sum(nil)) != m.Chunks[i] {
				reject(409, "Upload incomplete")
			}
			next = int64(i + 1)
			if time.Now().After(until) {
				break
			}
		}
	})
	var result map[string]any
	s.write(r.Context(), func(c *sql.Conn) {
		o := requireOp(c, r.Context(), library, op, u)
		if o.Kind != "UPLOAD" || (o.State != "PENDING" && o.State != "PUBLISHED") {
			reject(409, "Upload unavailable")
		}
		if o.State == "PENDING" {
			var count, total int64
			must(c.QueryRowContext(r.Context(), "SELECT COUNT(*),COALESCE(SUM(bytes),0) FROM chunks WHERE library=? AND operation=?", library, op).Scan(&count, &total))
			if count != int64(len(m.Chunks)) || total != m.Bytes {
				reject(409, "Upload incomplete")
			}
			exec(c, r.Context(), "UPDATE operations SET verified_chunks=MAX(verified_chunks,?) WHERE library=? AND id=?", next, library, op)
			if next == int64(len(m.Chunks)) {
				s.fault("before-publish-commit")
				exec(c, r.Context(), "UPDATE operations SET state='PUBLISHED',updated=? WHERE library=? AND id=?", s.now(), library, op)
			}
		}
		result = requireOp(c, r.Context(), library, op, u).receipt()
	})
	if result["state"] == "PUBLISHED" {
		s.fault("after-publish-commit")
	} else {
		s.fault("after-publication-progress-commit")
	}
	jsonOut(w, result)
}
func (s *service) acquire(w http.ResponseWriter, r *http.Request, u session) {
	var result map[string]any
	s.write(r.Context(), func(c *sql.Conn) {
		o := requireOp(c, r.Context(), r.PathValue("library"), r.PathValue("operation"), u)
		if o.Kind != "UPLOAD" || o.State != "PUBLISHED" {
			reject(404, "Version unavailable")
		}
		exec(c, r.Context(), "DELETE FROM leases WHERE expires<=?", s.now())
		if scalar(c, r.Context(), "SELECT COUNT(*) FROM leases WHERE user=?", u.User) >= 64 {
			reject(429, "Lease budget reached")
		}
		lease := id()
		at := s.now()
		exec(c, r.Context(), "INSERT INTO leases VALUES(?,?,?,?,?,?,?,?)", lease, o.Library, o.ID, u.User, u.Device, o.Digest, at+leaseSeconds, at+leaseMax)
		var m manifest
		must(json.Unmarshal([]byte(o.Manifest), &m))
		result = map[string]any{"lease": lease, "digest": o.Digest, "expires": at + leaseSeconds, "manifest": m}
	})
	jsonOut(w, result)
}
func (s *service) leased(c *sql.Conn, ctx context.Context, library, op, lease string, u session) (*operation, int64) {
	if !validID(lease) {
		reject(422, "Lease identity")
	}
	o := requireOp(c, ctx, library, op, u)
	var user, device, digest string
	var expires, deadline int64
	e := c.QueryRowContext(ctx, "SELECT user,device,digest,expires,deadline FROM leases WHERE id=? AND library=? AND operation=?", lease, library, op).Scan(&user, &device, &digest, &expires, &deadline)
	if e == sql.ErrNoRows {
		reject(409, "Download lease unavailable")
	}
	must(e)
	if user != u.User || device != u.Device || digest != o.Digest || expires <= s.now() || (o.State != "PUBLISHED" && o.State != "DELETING") {
		reject(409, "Download lease unavailable")
	}
	return o, deadline
}
func (s *service) renew(w http.ResponseWriter, r *http.Request, u session) {
	var result map[string]any
	s.write(r.Context(), func(c *sql.Conn) {
		o, deadline := s.leased(c, r.Context(), r.PathValue("library"), r.PathValue("operation"), r.PathValue("lease"), u)
		expires := min(s.now()+leaseSeconds, deadline)
		exec(c, r.Context(), "UPDATE leases SET expires=? WHERE id=?", expires, r.PathValue("lease"))
		result = map[string]any{"lease": r.PathValue("lease"), "digest": o.Digest, "expires": expires}
	})
	jsonOut(w, result)
}
func (s *service) download(w http.ResponseWriter, r *http.Request, u session) {
	var data []byte
	// At most one MiB is acquired while deletion is excluded. Socket output holds no DB lock.
	s.write(r.Context(), func(c *sql.Conn) {
		o, _ := s.leased(c, r.Context(), r.PathValue("library"), r.PathValue("operation"), r.URL.Query().Get("lease"), u)
		var m manifest
		must(json.Unmarshal([]byte(o.Manifest), &m))
		n := ordinal(r, m)
		f, e := os.Open(filepath.Join(s.folder(o.Library, o.ID), fmtChunk(n)))
		must(e)
		defer f.Close()
		data, e = io.ReadAll(io.LimitReader(f, wire+1))
		must(e)
		if int64(len(data)) != min(wire, m.Bytes-int64(n)*wire) || hash(data) != m.Chunks[n] {
			reject(507, "Chunk damaged")
		}
	})
	w.Header().Set("Content-Type", "application/octet-stream")
	_, e := w.Write(data)
	must(e)
}
func (s *service) delete(w http.ResponseWriter, r *http.Request, u session) {
	library, target := r.PathValue("library"), r.PathValue("target")
	op := r.URL.Query().Get("operation_id")
	expected := r.URL.Query().Get("expected_state")
	if !validID(op) || (expected != "" && expected != "PENDING") {
		reject(422, "Invalid deletion")
	}
	v := map[string]string{"target": target}
	if expected != "" {
		v["expected"] = expected
	}
	payload := string(encoded(v))
	fingerprint := hash([]byte(payload))
	s.write(r.Context(), func(c *sql.Conn) {
		old := lookup(c, r.Context(), library, op, u)
		if old != nil {
			if old.Kind != "DELETE" || old.Digest != fingerprint {
				reject(409, "Operation payload changed")
			}
			return
		}
		o := requireOp(c, r.Context(), library, target, u)
		if o.Kind != "UPLOAD" || (expected != "" && o.State != expected) {
			reject(409, "Upload state changed")
		}
		state := "DELETING"
		if o.Reserved == 0 {
			state = "DELETED"
		}
		if o.State != "DELETED" && o.State != "EXPIRED" {
			exec(c, r.Context(), "UPDATE operations SET state='DELETING' WHERE library=? AND id=?", library, target)
		}
		at := s.now()
		exec(c, r.Context(), "INSERT INTO operations(library,id,kind,digest,manifest,state,created,updated,cipher_bytes,reserved_bytes,target) VALUES(?,?,?,?,?,?,?,?,?,?,?)", library, op, "DELETE", fingerprint, payload, state, at, at, 0, 0, target)
		s.fault("before-delete-commit")
	})
	s.fault("after-delete-commit")
	s.reap(r.Context())
	var result map[string]any
	s.read(r.Context(), func(c *sql.Conn) { result = requireOp(c, r.Context(), library, op, u).receipt() })
	jsonOut(w, result)
}
func (s *service) reap(ctx context.Context) {
	// One service owns this directory. Concurrent reapers never interleave retirement with admission.
	if !s.reaping.TryLock() {
		return
	}
	defer s.reaping.Unlock()
	jobs := [][2]string{}
	s.write(ctx, func(c *sql.Conn) {
		exec(c, ctx, "DELETE FROM leases WHERE expires<=?", s.now())
		exec(c, ctx, "UPDATE operations SET state='EXPIRED' WHERE kind='UPLOAD' AND state='PENDING' AND updated<?", s.now()-pendingSeconds)
		rows, e := c.QueryContext(ctx, "SELECT library,id FROM operations o WHERE kind='UPLOAD' AND reserved_bytes>0 AND state IN ('EXPIRED','DELETING') AND NOT EXISTS(SELECT 1 FROM leases l WHERE l.library=o.library AND l.operation=o.id)")
		must(e)
		defer rows.Close()
		for rows.Next() {
			var job [2]string
			must(rows.Scan(&job[0], &job[1]))
			jobs = append(jobs, job)
		}
		must(rows.Err())
	})
	for _, job := range jobs {
		if ctx.Err() != nil {
			panic(ctx.Err())
		}
		folder := s.folder(job[0], job[1])
		entries, e := os.ReadDir(folder)
		if e != nil && !os.IsNotExist(e) {
			continue
		}
		retired := true
		for _, entry := range entries {
			if ctx.Err() != nil {
				panic(ctx.Err())
			}
			if entry.IsDir() || entry.Type()&os.ModeSymlink != 0 {
				retired = false
				break
			}
			if e = os.Remove(filepath.Join(folder, entry.Name())); e != nil && !os.IsNotExist(e) {
				retired = false
				break
			}
		}
		if !retired {
			continue
		}
		if e = os.Remove(folder); e != nil && !os.IsNotExist(e) {
			continue
		}
		if e = syncDir(filepath.Dir(folder)); e != nil && !os.IsNotExist(e) {
			continue
		}
		s.fault("after-payload-retire")
		s.write(ctx, func(c *sql.Conn) {
			exec(c, ctx, "DELETE FROM chunks WHERE library=? AND operation=?", job[0], job[1])
			exec(c, ctx, "UPDATE operations SET reserved_bytes=0,state=CASE WHEN state='EXPIRED' THEN 'EXPIRED' ELSE 'DELETED' END WHERE library=? AND id=?", job[0], job[1])
			exec(c, ctx, "UPDATE operations SET state='DELETED' WHERE library=? AND kind='DELETE' AND target=?", job[0], job[1])
			s.fault("before-retirement-commit")
		})
	}
}
