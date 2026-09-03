# Reference repositories

Cloned 2026-09-03 with `--depth 1` to `/tmp/kui-ref/` (outside the repository, never committed).

| Project | Local path | Commit | Last commit date | Status |
| --- | --- | --- | --- | --- |
| Kafbat Kafka UI | `/tmp/kui-ref/kafbat` | `fa485c2bd45cac713cd994c62bc2d458abd3f328` | 2026-09-03 | Active, primary reference |
| Provectus Kafka UI | `/tmp/kui-ref/provectus` | `83b5a60cc08501b570a0c4d0b4cdfceb1b88d6b7` | 2024-04-08 | Dormant since 2024-04; Kafbat is the community fork |
| Consdata Kouncil | `/tmp/kui-ref/consdata` | `6e2fb85e6ceac813c39f762eecd2f4bce1b31faf` | 2026-08-04 | Archived 2026-08-04 |

Re-clone with:

```
REF=/tmp/kui-ref
git clone --depth 1 https://github.com/kafbat/kafka-ui.git    "$REF/kafbat"
git clone --depth 1 https://github.com/provectus/kafka-ui.git "$REF/provectus"
git clone --depth 1 https://github.com/Consdata/kouncil.git   "$REF/consdata"
```
