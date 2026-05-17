.PHONY: setup dev-compile dev-serve test build

setup:
	npm install

dev-compile:
	sbt "~viewer/fastLinkJS"

dev-serve:
	npm run dev

test:
	sbt test

build:
	sbt "viewer/fullLinkJS"
	npm run build
