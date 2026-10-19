# Third-party software

## Argon2

The portable Argon2 reference implementation is copied from:

- Repository: https://github.com/P-H-C/phc-winner-argon2
- Tag: `20190702`
- Commit: `62358ba2123abd17fccf2a108a301d4b52c01a7c`
- License: [Vendor/argon2/LICENSE](Vendor/argon2/LICENSE), upstream dual Apache 2.0 / CC0 terms.

The application compiles `argon2.c`, `core.c`, `encoding.c`, `ref.c`, `thread.c`, and `blake2/blake2b.c`, with the upstream headers and `ARGON2_NO_THREADS=1`. Other upstream source files are retained for provenance and are not members of the application target. No custom cryptographic primitive has been substituted.

## ZIPFoundation

- Repository: https://github.com/weichsel/ZIPFoundation
- Version: `0.9.19`
- Commit: `02b6abe5f6eef7e3cbd5f247c5cc24e246efcfe0`
- License: MIT, distributed in the resolved Swift package.

Apple frameworks are supplied by the Xcode/iOS SDK under Apple's terms.
