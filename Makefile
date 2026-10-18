# adosaura — common targets.
#
#   make              build release + run unit tests
#   make test         unit tests only
#   make debug        build debug APK (untrimmed, faster iteration)
#   make deploy       build + test + install + grants
#   make deploy-d     build debug + test + install + grants
#   make grants       HyperOS permission grants (standalone)
#   make boot         bootstrap user-space toolchain (JDK, SDK, gradle)
#   make clean        delete build outputs
#   make debloat-scan scan phone for bloat.conf
#   make debloat-apply remove packages marked 'remove' in bloat.conf
#   make debloat-restore reinstall removed packages

ROOT    := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
TC      ?= $(HOME)/android-toolchain
PKG     := dev.aura.adosaura
GRADLE  := $(ROOT)/gradlew
ADB     := adb

# All shell targets use bash and inherit the toolchain env if present.
SHELL   := /bin/bash
ENV_SH  := $(TC)/env.sh

# Source env.sh when it exists so java/adb/sdkmanager are on PATH.
define with_env
	@if [ -f "$(ENV_SH)" ]; then source "$(ENV_SH)"; fi; $(1)
endef

# ───────────────────────────────────── default: release build + unit tests
.PHONY: all
all: check-env
	$(call with_env, cd "$(ROOT)" && $(GRADLE) :app:testDebugUnitTest :app:assembleRelease --console=plain)

# ───────────────────────────────────── unit tests only
.PHONY: test
test: check-env
	$(call with_env, cd "$(ROOT)" && $(GRADLE) :app:testDebugUnitTest --console=plain)

# ───────────────────────────────────── debug build (no R8, faster iteration)
.PHONY: debug
debug: check-env
	$(call with_env, cd "$(ROOT)" && $(GRADLE) :app:testDebugUnitTest :app:assembleDebug --console=plain)

# ───────────────────────────────────── full deploy (release)
.PHONY: deploy
deploy:
	"$(ROOT)scripts/deploy.sh"

# ───────────────────────────────────── full deploy (debug)
.PHONY: deploy-d
deploy-d:
	"$(ROOT)scripts/deploy.sh" --debug

# ───────────────────────────────────── HyperOS permission grants
.PHONY: grants
grants:
	"$(ROOT)scripts/adb-grants.sh"

# ───────────────────────────────────── toolchain bootstrap
.PHONY: boot
boot:
	"$(ROOT)scripts/bootstrap-toolchain.sh"

# ───────────────────────────────────── clean
.PHONY: clean
clean:
	cd "$(ROOT)" && $(GRADLE) clean --console=plain 2>/dev/null || true
	rm -rf "$(ROOT)app/build"

# ───────────────────────────────────── debloat helpers
.PHONY: debloat-scan debloat-apply debloat-restore debloat-status

debloat-scan:
	"$(ROOT)scripts/debloat.sh" scan

debloat-apply:
	"$(ROOT)scripts/debloat.sh" apply

debloat-restore:
	"$(ROOT)scripts/debloat.sh" restore

debloat-status:
	"$(ROOT)scripts/debloat.sh" status

# ───────────────────────────────────── lint / info
.PHONY: check-env devices

check-env:
	@test -f "$(ENV_SH)" || test -x "$(ROOT)gradlew" || { \
		echo "No toolchain at $(TC) and no gradlew wrapper."; \
		echo "Run:  make boot"; \
		exit 1; }

devices:
	@source "$(ENV_SH)" 2>/dev/null || true; $(ADB) devices -l

# ───────────────────────────────────── install APK (after building)
.PHONY: install
install:
	@source "$(ENV_SH)" 2>/dev/null || true; \
	APK="$(ROOT)app/build/outputs/apk/release/app-release.apk"; \
	if [ ! -f "$$APK" ]; then APK="$(ROOT)app/build/outputs/apk/debug/app-debug.apk"; fi; \
	if [ ! -f "$$APK" ]; then echo "No APK found — run 'make' or 'make debug' first." >&2; exit 1; fi; \
	$(ADB) install -r "$$APK"