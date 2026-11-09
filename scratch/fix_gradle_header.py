with open("app/build.gradle.kts", "r", encoding="utf-8") as f:
    lines = f.readlines()

# Put @file:Suppress at line 0
suppress_line = '@file:Suppress("ChromeOsAbiSupport")\n'
new_lines = [suppress_line, '\n']
for line in lines:
    if line.strip() == '@file:Suppress("ChromeOsAbiSupport")':
        continue
    new_lines.append(line)

with open("app/build.gradle.kts", "w", encoding="utf-8") as f:
    f.writelines(new_lines)

print("Fixed app/build.gradle.kts top annotations")
