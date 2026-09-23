#!/usr/bin/env bash
# adosaura — user-space Android toolchain bootstrap.
#
# Everything lands in ~/android-toolchain. Nothing touches the system and no
# sudo is used (Aura's rules: no raw sudo in chat, every change idempotent and
# announced). Safe to re-run: each step checks before it downloads or extracts
# and prints what it actually changed.
#
#   scripts/bootstrap-toolchain.sh
#   source ~/android-toolchain/env.sh    # then ./gradlew works

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="${ADOSAURA_TOOLCHAIN:-$HOME/android-toolchain}"
JAVA_DIR="$TC/jdk-17"
SDK="$TC/sdk"
GRADLE_DIR="$TC/gradle"
DL="$TC/downloads"

# Pinned on purpose: bump together with gradle/libs.versions.toml.
GRADLE_VERSION="8.11.1"
CMDLINE_REV="11076708"
SDK_PLATFORM="android-35"
SDK_BUILD_TOOLS="35.0.0"

JDK_URL="https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_REV}_latest.zip"
PLATFORM_TOOLS_URL="https://dl.google.com/android/repository/platform-tools-latest-linux.zip"
GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"

say() { printf '\033[35m::\033[0m %s\n' "$*"; }
warn() { printf '\033[33m!!\033[0m %s\n' "$*" >&2; }

fetch() { # fetch <url> <dest>
    local url="$1" dest="$2"
    if [[ -s "$dest" ]]; then
        say "download  cached $(basename "$dest")"
        return 0
    fi
    say "download  $(basename "$dest")"
    curl -fL --retry 3 --retry-delay 2 -o "$dest.part" "$url"
    mv "$dest.part" "$dest"
}

mkdir -p "$TC" "$DL" "$SDK"

# --------------------------------------------------------------- 1. JDK 17
if [[ -x "$JAVA_DIR/bin/java" ]]; then
    say "jdk       present: $("$JAVA_DIR/bin/java" -version 2>&1 | head -1)"
else
    fetch "$JDK_URL" "$DL/jdk17.tar.gz"
    say "jdk       extracting"
    mkdir -p "$JAVA_DIR"
    tar -xzf "$DL/jdk17.tar.gz" -C "$JAVA_DIR" --strip-components=1
    say "jdk       installed: $("$JAVA_DIR/bin/java" -version 2>&1 | head -1)"
fi

export JAVA_HOME="$JAVA_DIR"
export PATH="$JAVA_HOME/bin:$PATH"

# ----------------------------------------------------- 2. Android cmdline-tools
# The zip unpacks as cmdline-tools/ ; sdkmanager wants cmdline-tools/latest/.
if [[ -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
    say "sdk       cmdline-tools present"
else
    fetch "$CMDLINE_URL" "$DL/cmdline-tools.zip"
    say "sdk       extracting cmdline-tools"
    rm -rf "$SDK/cmdline-tools.tmp"
    mkdir -p "$SDK/cmdline-tools.tmp"
    unzip -q -o "$DL/cmdline-tools.zip" -d "$SDK/cmdline-tools.tmp"
    rm -rf "$SDK/cmdline-tools/latest"
    mkdir -p "$SDK/cmdline-tools"
    mv "$SDK/cmdline-tools.tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$SDK/cmdline-tools.tmp"
    say "sdk       cmdline-tools installed"
fi

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"

# ------------------------------------------------------- 3. platform-tools (adb)
if [[ -x "$SDK/platform-tools/adb" ]]; then
    say "adb       present: $("$SDK/platform-tools/adb" version | head -1)"
else
    fetch "$PLATFORM_TOOLS_URL" "$DL/platform-tools.zip"
    say "adb       extracting"
    unzip -q -o "$DL/platform-tools.zip" -d "$SDK"
    say "adb       installed: $("$SDK/platform-tools/adb" version | head -1)"
fi

# --------------------------------------------------- 4. SDK platform + build-tools
need_platform=true
[[ -d "$SDK/platforms/$SDK_PLATFORM" ]] && need_platform=false
need_bt=true
[[ -d "$SDK/build-tools/$SDK_BUILD_TOOLS" ]] && need_bt=false

if $need_platform || $need_bt; then
    say "sdk       accepting licenses"
    # 'yes' dies with SIGPIPE once sdkmanager exits; under pipefail that would
    # abort the script, so the pipeline is explicitly tolerated.
    yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
    pkgs=()
    $need_platform && pkgs+=("platforms;$SDK_PLATFORM")
    $need_bt && pkgs+=("build-tools;$SDK_BUILD_TOOLS")
    say "sdk       installing ${pkgs[*]}"
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "${pkgs[@]}"
else
    say "sdk       $SDK_PLATFORM + build-tools $SDK_BUILD_TOOLS present"
fi

# ------------------------------------------------------------- 5. Gradle 8.11.1
if [[ -x "$GRADLE_DIR/gradle-$GRADLE_VERSION/bin/gradle" ]]; then
    say "gradle    present $( "$GRADLE_DIR/gradle-$GRADLE_VERSION/bin/gradle" --version \
        | grep -m1 '^Gradle ' )"
else
    fetch "$GRADLE_URL" "$DL/gradle-$GRADLE_VERSION-bin.zip"
    say "gradle    extracting"
    unzip -q -o "$DL/gradle-$GRADLE_VERSION-bin.zip" -d "$GRADLE_DIR"
    say "gradle    installed $( "$GRADLE_DIR/gradle-$GRADLE_VERSION/bin/gradle" --version \
        | grep -m1 '^Gradle ' )"
fi
GRADLE_BIN="$GRADLE_DIR/gradle-$GRADLE_VERSION/bin/gradle"

# ------------------------------------------------------------- 6. env.sh
cat > "$TC/env.sh" <<EOF
# adosaura toolchain — source me, or let scripts/*.sh source me for you.
export JAVA_HOME="$JAVA_DIR"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="\$JAVA_HOME/bin:$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$GRADLE_DIR/gradle-$GRADLE_VERSION/bin:\$PATH"
export ADOSAURA_GRADLE="$GRADLE_BIN"
EOF
say "env       wrote $TC/env.sh"

# ------------------------------------------------- 7. Gradle wrapper in the repo
# The repo ships a wrapper so plain ./gradlew builds work without this script's
# PATH setup — that is what deploy.sh and CI use.
if [[ -f "$ROOT/gradlew" && -f "$ROOT/gradle/wrapper/gradle-wrapper.jar" ]]; then
    say "wrapper   present"
else
    say "wrapper   generating for Gradle $GRADLE_VERSION"
    ( cd "$ROOT" && "$GRADLE_BIN" wrapper --gradle-version "$GRADLE_VERSION" --distribution-type bin -q )
    say "wrapper   wrote gradlew + gradle/wrapper/"
fi

say "done"
echo
echo "  JDK    $JAVA_DIR"
echo "  SDK    $SDK  ($SDK_PLATFORM, build-tools $SDK_BUILD_TOOLS)"
echo "  adb    $SDK/platform-tools/adb"
echo "  gradle $GRADLE_VERSION (wrapper pinned in the repo)"
echo
echo "Next:  source $TC/env.sh && scripts/deploy.sh"
