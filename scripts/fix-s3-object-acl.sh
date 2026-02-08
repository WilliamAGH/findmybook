#!/usr/bin/env bash
set -euo pipefail

readonly ALL_USERS_URI="http://acs.amazonaws.com/groups/global/AllUsers"
readonly DEFAULT_SCOPE="all"
readonly DEFAULT_PROGRESS_EVERY=100
readonly CONSECUTIVE_ERROR_LIMIT=5

print_usage() {
  cat <<'USAGE'
Usage:
  scripts/fix-s3-object-acl.sh [options]

Options:
  --scope <all|images|json>  Limit which keys are processed (default: all)
  --prefix <path/>           Only process keys under this prefix
  --dry-run <true|false>     Report changes without writing ACLs (default: false)
  --verbose <true|false>     Log each changed key (default: false)
  --progress-every <number>  Print counters every N matched keys (default: 100)
  -h, --help                 Show this help text

Notes:
  - The script sources .env in the current working directory.
  - Required env vars after loading .env: S3_BUCKET
  - Optional env var: S3_SERVER_URL (used as --endpoint-url)
USAGE
}

error() {
  echo "ERROR: $*" >&2
}

normalize_bool() {
  local raw="${1:-false}"
  local lower
  lower="$(printf "%s" "$raw" | tr '[:upper:]' '[:lower:]')"
  case "$lower" in
    true|1|yes|y|on) echo "true" ;;
    false|0|no|n|off) echo "false" ;;
    *)
      error "Invalid boolean value '$raw'. Expected true/false."
      exit 1
      ;;
  esac
}

validate_scope() {
  local scope="$1"
  case "$scope" in
    all|images|json) ;;
    *)
      error "Invalid scope '$scope'. Expected one of: all, images, json."
      exit 1
      ;;
  esac
}

validate_positive_integer() {
  local raw="$1"
  if [[ ! "$raw" =~ ^[0-9]+$ ]]; then
    error "Invalid integer value '$raw'. Expected a positive number."
    exit 1
  fi
  if (( raw <= 0 )); then
    error "Invalid integer value '$raw'. Expected a value greater than 0."
    exit 1
  fi
}

require_command() {
  local command_name="$1"
  if ! command -v "$command_name" >/dev/null 2>&1; then
    error "Missing required command: $command_name"
    exit 1
  fi
}

is_image_key() {
  local key="$1"
  local lower
  lower="$(printf "%s" "$key" | tr '[:upper:]' '[:lower:]')"
  [[ "$lower" =~ \.(jpg|jpeg|png|webp|gif|avif|svg)$ ]]
}

matches_scope() {
  local key="$1"
  local scope="$2"
  local lower

  case "$scope" in
    all)
      return 0
      ;;
    images)
      is_image_key "$key"
      return
      ;;
    json)
      lower="$(printf "%s" "$key" | tr '[:upper:]' '[:lower:]')"
      [[ "$lower" == *.json ]]
      return
      ;;
    *)
      return 1
      ;;
  esac
}

main() {
  local scope="$DEFAULT_SCOPE"
  local prefix=""
  local dry_run="false"
  local verbose="false"
  local progress_every="$DEFAULT_PROGRESS_EVERY"

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --scope)
        scope="${2:-}"
        shift 2
        ;;
      --scope=*)
        scope="${1#*=}"
        shift
        ;;
      --prefix)
        prefix="${2:-}"
        shift 2
        ;;
      --prefix=*)
        prefix="${1#*=}"
        shift
        ;;
      --dry-run)
        dry_run="${2:-}"
        shift 2
        ;;
      --dry-run=*)
        dry_run="${1#*=}"
        shift
        ;;
      --verbose)
        verbose="${2:-}"
        shift 2
        ;;
      --verbose=*)
        verbose="${1#*=}"
        shift
        ;;
      --progress-every)
        progress_every="${2:-}"
        shift 2
        ;;
      --progress-every=*)
        progress_every="${1#*=}"
        shift
        ;;
      -h|--help)
        print_usage
        exit 0
        ;;
      *)
        error "Unknown argument: $1"
        print_usage
        exit 1
        ;;
    esac
  done

  validate_scope "$scope"
  validate_positive_integer "$progress_every"
  dry_run="$(normalize_bool "$dry_run")"
  verbose="$(normalize_bool "$verbose")"

  require_command aws
  require_command jq

  if [[ ! -f ".env" ]]; then
    error ".env file not found in current directory."
    exit 1
  fi

  set -a
  # shellcheck disable=SC1091
  source ./.env
  set +a

  if [[ -z "${S3_BUCKET:-}" ]]; then
    error "S3_BUCKET not found in .env."
    exit 1
  fi

  local -a endpoint_args=()
  if [[ -n "${S3_SERVER_URL:-}" ]]; then
    endpoint_args=(--endpoint-url "$S3_SERVER_URL")
  fi

  local continuation_token=""
  local page_number=0
  local scanned=0
  local checked=0
  local already_public=0
  local updated=0
  local would_update=0
  local errors=0
  local consecutive_errors=0
  local processed_page_keys=0

  echo "Repairing S3 object ACLs to public-read..."
  echo "Scope=$scope Prefix=$prefix DryRun=$dry_run Verbose=$verbose ProgressEvery=$progress_every Bucket=$S3_BUCKET"

  while :; do
    local -a list_command=(
      aws s3api list-objects-v2
      --bucket "$S3_BUCKET"
      --max-keys 1000
      --output json
    )
    if [[ -n "$prefix" ]]; then
      list_command+=(--prefix "$prefix")
    fi
    if [[ -n "$continuation_token" ]]; then
      list_command+=(--continuation-token "$continuation_token")
    fi
    if [[ ${#endpoint_args[@]} -gt 0 ]]; then
      list_command+=("${endpoint_args[@]}")
    fi

    local page_json
    page_json="$(AWS_PAGER="" "${list_command[@]}")"
    page_number=$((page_number + 1))

    local -a keys=()
    mapfile -t keys < <(printf "%s" "$page_json" | jq -r '.Contents[]?.Key')
    processed_page_keys="${#keys[@]}"
    scanned=$((scanned + processed_page_keys))
    echo "page=$page_number listedKeys=$processed_page_keys scanned=$scanned matched=$checked updated=$updated errors=$errors"

    local key
    for key in "${keys[@]}"; do
      if ! matches_scope "$key" "$scope"; then
        continue
      fi

      checked=$((checked + 1))
      if (( checked % progress_every == 0 )); then
        echo "progress matched=$checked alreadyPublic=$already_public updated=$updated wouldUpdate=$would_update errors=$errors lastKey=$key"
      fi

      local acl_json acl_stderr
      acl_stderr="$(mktemp)"
      acl_json="$(AWS_PAGER="" aws s3api get-object-acl --bucket "$S3_BUCKET" --key "$key" "${endpoint_args[@]}" --output json 2>"$acl_stderr")" || true
      if [[ -z "$acl_json" ]]; then
        errors=$((errors + 1))
        consecutive_errors=$((consecutive_errors + 1))
        error "[$checked] Failed to read ACL for key '$key': $(cat "$acl_stderr")"
        rm -f "$acl_stderr"
        if (( consecutive_errors >= CONSECUTIVE_ERROR_LIMIT )); then
          error "Halting: $CONSECUTIVE_ERROR_LIMIT consecutive failures indicate a systemic problem (credentials, network, permissions)."
          exit 1
        fi
        continue
      fi
      rm -f "$acl_stderr"
      consecutive_errors=0

      if printf "%s" "$acl_json" | jq -e --arg uri "$ALL_USERS_URI" '.Grants[]? | select(.Permission == "READ" and .Grantee.Type == "Group" and .Grantee.URI == $uri)' >/dev/null; then
        already_public=$((already_public + 1))
        continue
      fi

      if [[ "$dry_run" == "true" ]]; then
        would_update=$((would_update + 1))
        if [[ "$verbose" == "true" ]]; then
          echo "DRY-RUN: [$checked] Would set public-read ACL for key '$key'"
        fi
      else
        local put_stderr
        put_stderr="$(mktemp)"
        if AWS_PAGER="" aws s3api put-object-acl --bucket "$S3_BUCKET" --key "$key" --acl public-read "${endpoint_args[@]}" >/dev/null 2>"$put_stderr"; then
          updated=$((updated + 1))
          consecutive_errors=0
          rm -f "$put_stderr"
          if [[ "$verbose" == "true" ]]; then
            echo "UPDATED: [$checked] Set public-read ACL for key '$key'"
          fi
        else
          errors=$((errors + 1))
          consecutive_errors=$((consecutive_errors + 1))
          error "[$checked] Failed to set ACL for key '$key': $(cat "$put_stderr")"
          rm -f "$put_stderr"
          if (( consecutive_errors >= CONSECUTIVE_ERROR_LIMIT )); then
            error "Halting: $CONSECUTIVE_ERROR_LIMIT consecutive failures indicate a systemic problem (credentials, network, permissions)."
            exit 1
          fi
        fi
      fi
    done

    local is_truncated
    is_truncated="$(printf "%s" "$page_json" | jq -r '.IsTruncated // false')"
    if [[ "$is_truncated" != "true" ]]; then
      break
    fi

    continuation_token="$(printf "%s" "$page_json" | jq -r '.NextContinuationToken // empty')"
    if [[ -z "$continuation_token" ]]; then
      break
    fi

    if (( processed_page_keys == 0 )); then
      break
    fi
  done

  if (( checked == 0 )); then
    echo "No matching objects found for scope '$scope' and prefix '$prefix'."
  fi

  echo "ACL repair complete. scanned=$scanned matched=$checked alreadyPublic=$already_public updated=$updated wouldUpdate=$would_update errors=$errors dryRun=$dry_run scope=$scope prefix=$prefix pages=$page_number"
}

main "$@"
