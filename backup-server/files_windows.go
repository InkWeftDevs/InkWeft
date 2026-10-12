// SPDX-License-Identifier: AGPL-3.0-or-later
//go:build windows

package main

import (
	"golang.org/x/sys/windows"
	"syscall"
)

func publishFile(source, target string) error {
	a, e := windows.UTF16PtrFromString(source)
	if e != nil {
		return e
	}
	b, e := windows.UTF16PtrFromString(target)
	if e != nil {
		return e
	}
	return windows.MoveFileEx(a, b, windows.MOVEFILE_WRITE_THROUGH)
}
func syncDir(path string) error { return nil } // Directory rename durability is handled by WRITE_THROUGH.
func freeBytes(path string) (int64, error) {
	p, e := syscall.UTF16PtrFromString(path)
	if e != nil {
		return 0, e
	}
	var available, total, free uint64
	e = windows.GetDiskFreeSpaceEx(p, &available, &total, &free)
	return int64(available), e
}
