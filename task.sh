#!/bin/sh
set -eu
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1
G=./gradlew

images() {
  $G bootJar --quiet
  for s in ledger monitor auth extbank; do
    docker build --quiet -f deploy/Dockerfile --build-arg SERVICE="$s" -t "tally/$s:dev" . >/dev/null
  done
  echo "images built"
}

up() {
  [ -f .env ] || sh scripts/seed_env.sh
  images
  docker compose up -d --wait --wait-timeout 180
  echo "stack ready"
}

down() {
  docker compose down --remove-orphans
}

target="${1:-}"
[ "$#" -gt 0 ] && shift
case "$target" in
  setup)
    $G --version
    [ -f .env ] || sh scripts/seed_env.sh
    $G classes testClasses testFixturesClasses
    ;;
  fmt) $G spotlessApply ;;
  lint) $G checkstyleMain checkstyleTest checkstyleTestFixtures ;;
  typecheck) $G compileJava compileTestJava compileTestFixturesJava ;;
  test) $G test ;;
  e2e)
    $G integrationTest jacocoTestCoverageVerification
    images
    $G :proof:proofTest -Pproof.size=ci
    up
    trap down EXIT
    $G :proof:e2eTest
    ;;
  check)
    $G spotlessCheck
    sh task.sh lint
    sh task.sh typecheck
    sh task.sh test
    sh task.sh e2e
    echo "check passed"
    ;;
  run) up ;;
  down) down ;;
  images) images ;;
  seed) sh scripts/seed_env.sh "$@" ;;
  proof) $G :proof:proofTest "$@" ;;
  mutation) $G :ledger-core:pitest ;;
  openapi) $G :ledger:integrationTest --tests '*OpenApiDriftIT' -Dopenapi.write=true ;;
  invariants) docker compose run --rm --no-deps ledger check-invariants "$@" ;;
  verify-audit) docker compose run --rm --no-deps ledger verify-audit "$@" ;;
  demo)
    up
    $G :proof:demo --quiet --console=plain
    ;;
  demo-record)
    up
    mkdir -p build/demo
    $G :proof:demo --quiet --console=plain -PcmdArgs="--cast build/demo/demo.cast"
    docker run --rm -v "$(pwd)/build/demo:/data" ghcr.io/asciinema/agg:1.9.0 \
      --cols 100 --rows 32 --speed 2 --idle-time-limit 1 /data/demo.cast /data/demo.gif
    cp build/demo/demo.gif docs/demo.gif
    echo "wrote docs/demo.gif"
    ;;
  eval)
    images
    $G :proof:eval --quiet --console=plain -Pproof.size=eval -PcmdArgs="$*"
    ;;
  render) $G :proof:render --quiet --console=plain -PcmdArgs="$*" ;;
  clean)
    $G clean
    rm -rf build
    ;;
  reset)
    docker compose down --volumes --remove-orphans
    rm -rf build/eval build/demo
    up
    ;;
  *)
    echo "usage: sh task.sh {setup|fmt|lint|typecheck|test|e2e|check|run|down|images|seed|proof|mutation|openapi|invariants|verify-audit|demo|demo-record|eval|render|clean|reset}" >&2
    exit 2
    ;;
esac
