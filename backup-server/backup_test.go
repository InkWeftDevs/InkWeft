// SPDX-License-Identifier: AGPL-3.0-or-later
package main

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestQuotaLeaseCrashRecoveryAndIsolation(t *testing.T) {
	s, e := openService(filepath.Join(t.TempDir(), "metadata.db"), 200, 400)
	if e != nil {
		t.Fatal(e)
	}
	defer s.db.Close()
	if e = s.addUser("alice", "alice-password-123"); e != nil {
		t.Fatal(e)
	}
	if e = s.addUser("bob", "bob-password-456"); e != nil {
		t.Fatal(e)
	}
	at := int64(1800000000)
	s.now = func() int64 { return at }
	httpServer := httptest.NewServer(s.handler())
	defer httpServer.Close()
	call := func(method, path, token string, body []byte, status int) map[string]any {
		t.Helper()
		r, e := http.NewRequest(method, httpServer.URL+path, bytes.NewReader(body))
		if e != nil {
			t.Fatal(e)
		}
		if token != "" {
			r.Header.Set("Authorization", "Bearer "+token)
		}
		r.Header.Set("Content-Type", "application/json")
		response, e := http.DefaultClient.Do(r)
		if e != nil {
			t.Fatal(e)
		}
		defer response.Body.Close()
		data, _ := io.ReadAll(response.Body)
		if response.StatusCode != status {
			t.Fatalf("%s %s: %d %s", method, path, response.StatusCode, data)
		}
		v := map[string]any{}
		json.Unmarshal(data, &v)
		return v
	}
	alice := call("POST", "/v1/sessions", "", encoded(map[string]string{"username": "alice", "password": "alice-password-123", "device": id()}), 200)["token"].(string)
	bob := call("POST", "/v1/sessions", "", encoded(map[string]string{"username": "bob", "password": "bob-password-456", "device": id()}), 200)["token"].(string)
	library, op := id(), id()
	base := "/v2/libraries/" + library
	call("PUT", base, alice, nil, 200)
	data := bytes.Repeat([]byte{42}, 100)
	m := manifest{Bytes: 100, PlainBytes: 1, Format: "inkweft.encrypted-backup.v2", Chunks: []string{hash(data)}}
	first := call("PUT", base+"/uploads/"+op, alice, encoded(m), 200)
	same := call("PUT", base+"/uploads/"+op, alice, encoded(m), 200)
	if first["digest"] != same["digest"] {
		t.Fatal("idempotency")
	}
	call("GET", base+"/uploads/"+op, bob, nil, 404)
	call("POST", base+"/uploads/"+op+"/publish", alice, nil, 409)
	s.fault = func(phase string) {
		if phase == "after-file-publish" {
			panic(httpError{507, "Injected crash"})
		}
	}
	call("PUT", base+"/uploads/"+op+"/chunks/0", alice, data, 507)
	inventory := call("GET", base+"/uploads/"+op, alice, nil, 200)
	if len(inventory["received"].([]any)) != 0 {
		t.Fatal("uncommitted file receipt")
	}
	s.fault = func(string) {}
	call("PUT", base+"/uploads/"+op+"/chunks/0", alice, data, 200)
	// Metadata receipts do not prove bytes still match at publication time.
	chunkPath := filepath.Join(s.folder(library, op), "00000000.chunk")
	if e = os.WriteFile(chunkPath, bytes.Repeat([]byte{41}, 100), 0600); e != nil {
		t.Fatal(e)
	}
	call("POST", base+"/uploads/"+op+"/publish", alice, nil, 409)
	if e = os.WriteFile(chunkPath, data, 0600); e != nil {
		t.Fatal(e)
	}
	s.fault = func(phase string) {
		if phase == "after-publish-commit" {
			panic(httpError{507, "Lost response"})
		}
	}
	call("POST", base+"/uploads/"+op+"/publish", alice, nil, 507)
	s.fault = func(string) {}
	if call("GET", base+"/operations/"+op, alice, nil, 200)["state"] != "PUBLISHED" {
		t.Fatal("lost publish receipt")
	}
	lease := call("POST", base+"/versions/"+op+"/leases", alice, nil, 200)["lease"].(string)
	deletion := id()
	deleted := call("DELETE", base+"/versions/"+op+"?operation_id="+deletion, alice, nil, 200)
	if deleted["state"] != "DELETING" {
		t.Fatal("lease did not protect payload")
	}
	call("POST", base+"/versions/"+op+"/leases", alice, nil, 404)
	call("GET", base+"/versions/"+op+"/chunks/0?lease="+lease, alice, nil, 200)
	call("GET", base+"/versions/"+op+"/chunks/0?lease="+lease, bob, nil, 404)
	call("DELETE", base+"/versions/"+op+"/leases/"+lease, alice, nil, 200)
	if call("GET", base+"/operations/"+deletion, alice, nil, 200)["state"] != "DELETED" {
		t.Fatal("retirement")
	}
	capacity := call("GET", "/v2/capacity", alice, nil, 200)
	if capacity["accountReserved"].(float64) != 0 {
		t.Fatal("quota not released")
	}
	call("PUT", base+"/uploads/"+op, alice, encoded(m), 200)
	call("PUT", base+"/uploads/"+op+"/chunks/0", alice, data, 409)
	// Atomic reservations allow exactly two 100-byte operations, regardless of concurrent begin order.
	var wg sync.WaitGroup
	statuses := make(chan int, 4)
	for range 4 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			r, _ := http.NewRequest("PUT", httpServer.URL+base+"/uploads/"+id(), bytes.NewReader(encoded(m)))
			r.Header.Set("Authorization", "Bearer "+alice)
			response, e := http.DefaultClient.Do(r)
			if e != nil {
				statuses <- 0
				return
			}
			response.Body.Close()
			statuses <- response.StatusCode
		}()
	}
	wg.Wait()
	close(statuses)
	success := 0
	for status := range statuses {
		if status == 200 {
			success++
		} else if status != 507 {
			t.Fatalf("status %d", status)
		}
	}
	if success != 2 {
		t.Fatalf("reserved %d instead of 2", success)
	}
	at += pendingSeconds + 1
	s.reap(context.Background())
	alice = call("POST", "/v1/sessions", "", encoded(map[string]string{"username": "alice", "password": "alice-password-123", "device": id()}), 200)["token"].(string)
	if call("GET", "/v2/capacity", alice, nil, 200)["accountReserved"].(float64) != 0 {
		t.Fatal("idle expiry")
	}
	call("DELETE", "/v1/sessions/current", alice, nil, 200)
	call("GET", "/v2/capacity", alice, nil, 401)
}

func TestCanonicalManifestVector(t *testing.T) {
	m := manifest{Bytes: 1048800, PlainBytes: 1048576, Format: "inkweft.encrypted-backup.v2", Chunks: []string{strings.Repeat("a", 64), strings.Repeat("b", 64)}}
	if hash(encoded(m)) != "6a454883e1b512dbd28e64b47902009a349743d3c1215c7ab3f4852ef7adeb3c" {
		t.Fatal("Canonical manifest digest differs from Android/Python vector")
	}
}

func TestCancelledWriterWaitAndSchemaRefusal(t *testing.T) {
	path := filepath.Join(t.TempDir(), "metadata.db")
	s, e := openService(path, 1<<30, 2<<30)
	if e != nil {
		t.Fatal(e)
	}
	defer s.db.Close()
	s.writes <- struct{}{}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	start := time.Now()
	func() {
		defer func() {
			if recover() == nil {
				t.Error("cancelled wait accepted")
			}
		}()
		s.write(ctx, func(*sql.Conn) { t.Error("cancelled write ran") })
	}()
	<-s.writes
	if time.Since(start) > time.Second {
		t.Fatal("cancelled writer waited")
	}
	_, e = s.db.Exec("PRAGMA user_version=1")
	if e != nil {
		t.Fatal(e)
	}
	other, e := openService(path, 1<<30, 2<<30)
	if e == nil {
		other.db.Close()
		t.Fatal("legacy DB accepted silently")
	}
}
