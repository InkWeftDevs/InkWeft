import unittest

from android_privacy_fault_probe import PACKAGE, mount_path, validate_mount


class FaultScopeTest(unittest.TestCase):
    def test_only_an_exact_private_tmpfs_is_accepted(self):
        run = '11111111-2222-4333-8444-555555555555'
        path = mount_path('/data/user/0/' + PACKAGE, run)
        validate_mount(f'fixture {path} tmpfs rw,size=8192k 0 0', path)
        workspace = 'org.inkweft.app.a0.workspace'
        self.assertEqual(f'/data/user/0/{workspace}/cache/v47-space-{run}', mount_path('/data/user/0/' + workspace, run, workspace))
        for data in ('/data', '/sdcard', '/data/user/0/org.inkweft.app.a0.workspace', '/data/user/0/' + PACKAGE + '/cache/..'):
            with self.assertRaises(ValueError):
                mount_path(data, run)
        for mounts in (f'fixture {path} ext4 rw 0 0', f'fixture {path} tmpfs ro 0 0', f'fixture {path}-other tmpfs rw 0 0', ''):
            with self.assertRaises(ValueError):
                validate_mount(mounts, path)


if __name__ == '__main__':
    unittest.main()
