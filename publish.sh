#!/usr/bin/env bash
# 一键发布：创建 GitHub 仓库 → 推送 → 打 tag → 上传 APK 到 Release
#
# 前置：gh auth login（或 export GH_TOKEN=...），git 已配置 user.name/email
#
# 用法：
#   ./publish.sh                 # 默认仓库名 android-gcode-sender，公开
#   REPO_NAME=my-sender ./publish.sh
#   VISIBILITY=private ./publish.sh

set -euo pipefail
cd "$(dirname "$0")"

REPO_NAME="${REPO_NAME:-android-gcode-sender}"
VISIBILITY="${VISIBILITY:-public}"
TAG="${TAG:-v1.0.0}"
APK="release/KvenjoyGcodeSender-1.0.apk"

echo "==> 检查 GitHub 认证"
gh auth status >/dev/null 2>&1 || { echo "请先执行：gh auth login"; exit 1; }
OWNER="$(gh api user -q .login)"
echo "    账号：$OWNER"

echo "==> 检查工作区"
if [ ! -d .git ]; then
  git init -q
  git branch -M main
fi
git add -A
if ! git diff --cached --quiet; then
  git commit -q -m "release: $TAG"
fi

echo "==> 创建并推送仓库 $OWNER/$REPO_NAME"
if gh repo view "$OWNER/$REPO_NAME" >/dev/null 2>&1; then
  git remote get-url origin >/dev/null 2>&1 || git remote add origin "https://github.com/$OWNER/$REPO_NAME.git"
  git push -u origin main
else
  gh repo create "$REPO_NAME" "--$VISIBILITY" --source=. --remote=origin --push
fi

echo "==> 打 tag $TAG"
git tag -a "$TAG" -m "$TAG" 2>/dev/null || true
git push origin "$TAG"

echo "==> 创建 Release"
if [ -f "$APK" ]; then
  gh release create "$TAG" "$APK#GCode Sender $TAG (APK)" \
    --title "GCode Sender $TAG" \
    --notes-file RELEASE_NOTES.md
else
  gh release create "$TAG" --title "GCode Sender $TAG" --notes-file RELEASE_NOTES.md
fi

echo "==> 完成：https://github.com/$OWNER/$REPO_NAME/releases/tag/$TAG"
