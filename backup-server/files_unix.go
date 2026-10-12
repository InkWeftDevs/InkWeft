// SPDX-License-Identifier: AGPL-3.0-or-later
//go:build linux

package main

import (
	"golang.org/x/sys/unix"
	"os"
	"path/filepath"
)

func syncDir(path string) error {
	f, e := os.Open(path)
	if e != nil {
		return e
	}
	defer f.Close()
	return f.Sync()
}
func publishFile(source, target string) error {
	if e := os.Rename(source, target); e != nil {
		return e
	}
	return syncDir(filepath.Dir(target))
}
func freeBytes(path string) (int64, error) {
	var v unix.Statfs_t
	e := unix.Statfs(path, &v)
	return int64(v.Bavail) * int64(v.Bsize), e
}
