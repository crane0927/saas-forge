# Password blocklist attribution

The generated password digest artifact includes the SecLists 2026.1 file
`Passwords/Common-Credentials/100k-most-used-passwords-NCSC.txt` and the repository list
`saas-forge-passwords.txt`.

SecLists is Copyright (c) 2018 Daniel Miessler and is distributed under the MIT License:
https://github.com/danielmiessler/SecLists/blob/2026.1/LICENSE

The upstream source file SHA-256 is
`c2e5696882c603b76bb67a47ee970897e5a76fc4c3f5547abe3d0ca340c576e0`.

After obtaining that exact upstream file, rebuild offline from the repository root with:

```shell
python3 scripts/build-password-blocklist.py \
  --seclists /path/to/100k-most-used-passwords-NCSC.txt \
  --repository-list services/iam-service/src/main/password-blocklist/saas-forge-passwords.txt \
  --output-directory services/iam-service/src/main/resources/password-blocklist
```
