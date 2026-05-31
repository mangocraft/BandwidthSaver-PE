import re

with open("src/main/resources/config.yml", "r", encoding="utf-8") as f:
    config_content = f.read()

with open("README.md", "r", encoding="utf-8") as f:
    readme_content = f.read()

# We want to replace everything between ```yaml and ``` at the end of the file
pattern = r'(## Configuration \| 配置文件\n\n```yaml\n).*?(```\n?)'
replacement = r'\g<1>' + config_content.replace('\\', '\\\\') + r'\n\g<2>'

new_readme = re.sub(pattern, replacement, readme_content, flags=re.DOTALL)

with open("README.md", "w", encoding="utf-8") as f:
    f.write(new_readme)

print("Done")
