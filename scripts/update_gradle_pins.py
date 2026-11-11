import sys
from pathlib import Path

gradle_file = Path("app/build.gradle.kts")
content = gradle_file.read_text(encoding="utf-8")

old_sha = 'val mcv2NpuW8a8ArtifactSha256 = "d687f414868e11b47049ac0566b7087f767abcb2a7ca02d0edad01e1437d4ac9"'
new_sha = 'val mcv2NpuW8a8ArtifactSha256 = "d60e18b2c64a11c25256969b24532747c06d9cad10327ec68281cd1ac0e94b83"'

old_bytes = "val mcv2NpuW8a8ArtifactBytes = 55_615_832L"
new_bytes = "val mcv2NpuW8a8ArtifactBytes = 55_628_120L"

assert old_sha in content, f"Could not find {old_sha}"
assert old_bytes in content, f"Could not find {old_bytes}"

content = content.replace(old_sha, new_sha).replace(old_bytes, new_bytes)
gradle_file.write_text(content, encoding="utf-8")
print("Updated app/build.gradle.kts successfully!")
