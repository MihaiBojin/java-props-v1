PKGNAME = props
SHELL := /bin/bash
BUILDIFIER_VERSION := 4.0.1

# Determine the OS
KERNEL=$(shell uname -s)
ifeq (Darwin,$(KERNEL))
	OS_NAME=osx
else ifeq (Linux,$(KERNEL))
	OS_NAME=linux64
else
	OS_NAME=unsupported
endif
LIB=lib

.DEFAULT_GOAL := build

# Determine the current commit's git hash and identify any version tags
GITHASH := $(shell git log -n1 --pretty='%H')
VERSION_TAG := $(shell git describe --exact-match --tags "$(GITHASH)" 2>/dev/null)

.PHONY: clean
clean:
	@echo "==> Cleaning project artifacts and metadata"
	rm -rf com/ module-info.class docs/javadoc
	bazelisk clean

.PHONY: build
build: check
	@echo "==> Building $(PKGNAME)..."
	bazelisk build //java-props-core/...

test: check
	@echo "==> Running tests for $(PKGNAME)..."
	bazelisk test //java-props-core/...

.PHONY: fmt
fmt:
	@echo ""
	@echo "==> Formatting Bazel build files..."
	$(LIB)/buildifier $(shell find . -type f \( -iname BUILD -or -iname BUILD.bazel \))

	@echo ""
	@echo "==> Formatting JAVA files..."
	bazelisk run //java-props-core/src/main:google-java-format
	bazelisk run //java-props-benchmark/src/main:google-java-format

.PHONY: fmtcheck
fmtcheck:
	@echo ""
	@echo "==> Ensuring that the JAVA code is properly formatted..."
	bazelisk run //java-props-core/src/main:google-java-format-check
	bazelisk run //java-props-benchmark/src/main:google-java-format-check

	@echo ""
	@echo "==> Ensuring all Bazel build files are properly formatted..."
	$(LIB)/buildifier --lint=warn $(shell find . -type f \( -iname BUILD -or -iname BUILD.bazel \))

.PHONY: benchmark
benchmark:
	@echo ""
	@echo "==> Running benchmarks..."
	bazelisk run //java-props-benchmark/src/main:jmh

.PHONY: vet
vet:
	@echo ""
	@echo "==> Running Checkstyle..."
	bazelisk build //java-props-core/src/main:checkstyle

.PHONY: generate-pom-version
generate-pom-version:
ifeq (, $(VERSION_TAG))
	$(error "Could not find a tag for commit hash: $(GITHASH)")
endif
	@echo ""
	@echo "==> Updating the release version..."
	echo "$(VERSION_TAG:v%=%)" > release/VERSION

.PHONY: assemble-maven
assemble-maven: check_sdkman
ifeq (0.0.0,$(shell cat release/VERSION))
	$(error "Before running this target, make sure to generate a VERSION file with the _generate-pom-version_ target")
endif
	@echo ""
	@echo "==> Assembling JAR artifacts for publishing to Maven Central..."
	cp java-props-core/src/main/java/module-info.template java-props-core/src/main/java/module-info.java
	bazelisk build //java-props-core/src/main:assemble-maven

.PHONY: deploy-maven
deploy-maven: assemble-maven
ifeq (, ${DEPLOY_MAVEN_USERNAME})
	$(error "Cannot assemble a JAR without a value for DEPLOY_MAVEN_USERNAME")
endif
ifeq (, ${DEPLOY_MAVEN_PASSWORD})
	$(error "Cannot assemble a JAR without a value for DEPLOY_MAVEN_PASSWORD")
endif
	@echo ""
	@echo "==> Deploying JAR artifacts to Maven Central"
	bazelisk run //java-props-core/src/main:deploy-maven -- release --gpg

.PHONY: javadoc
BASEDIR:=$(shell pwd)
TMPDIR := $(shell mktemp -d)
javadoc:
	@echo ""
	@echo "==> Updating the project's JavaDocs"
	@bazelisk build //java-props-core/src/main:assemble-maven

	@echo "==> Unpacking javadocs"
	cp bazel-bin/java-props-core/src/main/com.mihaibojin.props:props-core-javadoc.jar $(TMPDIR)/javadoc.jar
	cd $(TMPDIR) && jar xf javadoc.jar
	rm -f $(TMPDIR)/javadoc.jar
	rm -rf $(BASEDIR)/docs/javadoc
	mv $(TMPDIR) $(BASEDIR)/docs/javadoc

.PHONY: setup
setup: git-hooks setup_sdkman setup_bazelisk setup_buildifier

.PHONY: check
check: check_sdkman check_bazelisk check_buildifier

.PHONY: git-hooks
git-hooks:
	@echo ""
	@echo "==> Ensuring all git hooks are linked..."
	find .git/hooks -type l -exec rm {} \;
	find .githooks -type f -exec ln -sf ../../{} .git/hooks/ \;

.PHONY: check_sdkman
check_sdkman:
ifeq (,$(wildcard ~/.sdkman/bin/sdkman-init.sh))
	$(error "Please run 'make setup_sdkman'")
endif

.PHONY: check_bazelisk
check_bazelisk:
ifeq (, $(shell which bazelisk))
	$(error "Please add '$(shell go env GOPATH)/bin' to your current PATH and run 'make setup_bazelisk'")
endif

.PHONY: check_buildifier
check_buildifier:
ifeq (,$(wildcard $(LIB)/buildifier))
	$(error "Please run 'make setup_buildifier'")
endif

.PHONY: setup_sdkman
setup_sdkman:
ifeq (,$(wildcard ~/.sdkman/bin/sdkman-init.sh))
	@echo
	@echo "Installing SDKman..."
	curl -s "https://get.sdkman.io?rcupdate=false" | bash
	chmod a+x ~/.sdkman/bin/sdkman-init.sh
	@echo
	@echo "Downloading JDK..."
	bash -c ". ~/.sdkman/bin/sdkman-init.sh && sdk install java"
endif
	@echo "SDKman installed or present."

.PHONY: setup_bazelisk
setup_bazelisk:
ifeq (, $(shell which bazelisk))
ifeq (, $(shell which go))
	$(error "Bazelisk is not installed and golang is not available")
endif
	@echo ""
	@echo "==> Installing bazelisk..."
	go get github.com/bazelbuild/bazelisk
endif
	@echo "Bazelisk installed or present."

.PHONY: setup_buildifier
setup_buildifier:
ifeq (,$(wildcard $(LIB)/buildifier))
	@echo ""
	@echo "==> Installing buildifier..."
	mkdir -p $(LIB)

ifeq (Darwin,$(KERNEL))
	curl -sLo $(LIB)/buildifier https://github.com/bazelbuild/buildtools/releases/download/$(BUILDIFIER_VERSION)/buildifier.mac
else ifeq (Linux,$(KERNEL))
	curl -sLo $(LIB)/buildifier https://github.com/bazelbuild/buildtools/releases/download/$(BUILDIFIER_VERSION)/buildifier
else
	$(error Cannot install buildifier on $(KERNEL); please install manually!)
endif
	chmod a+rx $(LIB)/buildifier
endif
	@echo "Buildifier installed or present."
