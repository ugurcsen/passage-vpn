#!/usr/bin/env python3
"""Renders docs/api.md from the running backend's OpenAPI document.

Usage:
    python3 scripts/gen_api_docs.py > docs/api.md          # live backend on :8080
    python3 scripts/gen_api_docs.py spec.json > docs/api.md # from a saved file
    python3 scripts/gen_api_docs.py http://host:8080 > docs/api.md

The output is a compact, human-oriented reference: service info, security scheme
and one section per path with method, summary, parameters and response codes.
"""

import json
import sys
import urllib.request

METHODS = ["get", "put", "post", "delete", "patch", "options", "head"]


def load_spec(source):
    if source.startswith("http://") or source.startswith("https://"):
        with urllib.request.urlopen(source) as resp:
            return json.load(resp)
    with open(source) as fh:
        return json.load(fh)


def type_of(schema, depth=0):
    if schema is None:
        return "unknown"
    if "$ref" in schema:
        return schema["$ref"].rsplit("/", 1)[-1]
    t = schema.get("type")
    if t == "array":
        return f"list[{type_of(schema.get('items'), depth + 1)}]"
    if t == "object":
        return "object"
    if t in ("integer", "number", "string", "boolean"):
        return t
    if "anyOf" in schema or "oneOf" in schema:
        return "|".join(type_of(s) for s in schema.get("anyOf") or schema.get("oneOf"))
    if "enum" in schema:
        return "|".join(str(v) for v in schema["enum"])
    return t or "object"


def render_schema(name, schema):
    lines = [f"### {name}", ""]
    props = schema.get("properties") or {}
    required = set(schema.get("required") or [])
    if props:
        lines.append("| Field | Type | Required |")
        lines.append("|---|---|---|")
        for pname, pschema in sorted(props.items()):
            marker = "yes" if pname in required else "no"
            lines.append(f"| `{pname}` | {type_of(pschema)} | {marker} |")
    else:
        lines.append("_No fields._")
    lines.append("")
    return "\n".join(lines)


def main():
    source = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080/v3/api-docs"
    spec = load_spec(source)

    out = []
    info = spec.get("info", {})
    out.append(f"# API Reference — {info.get('title', 'OpenVPN Panel')}")
    out.append("")
    out.append("> Generated from the live OpenAPI document. Endpoints under `/api/admin/**`")
    out.append("> require an `ADMIN` (or scoped `GROUP_ADMIN`) role; `/api/portal/**` endpoints are")
    out.append("> self-service. `docs/api.md` is regenerated with `make api-docs`.")
    out.append("")
    if info.get("version"):
        out.append(f"- **Version**: {info['version']}")
    servers = spec.get("servers") or []
    for server in servers:
        out.append(f"- **Base URL**: `{server.get('url')}`")
    out.append("")

    security = spec.get("components", {}).get("securitySchemes", {})
    if security:
        out.append("## Authentication")
        out.append("")
        for name, scheme in security.items():
            out.append(f"- **{name}** (`{scheme.get('type')}`): `{scheme.get('scheme', scheme.get('in', ''))}` — {scheme.get('description', '')}")
        out.append("")
        out.append("Login via `POST /api/auth/login` (or `/api/auth/mfa`) and pass the returned")
        out.append("access token as `Authorization: Bearer <token>`. Automation can instead use an")
        out.append("API token as `X-API-Token: opnl_...` (see the Admin - API tokens endpoints).")
        out.append("")

    out.append("## Endpoints")
    out.append("")
    for path in sorted(spec.get("paths", {})):
        methods = spec["paths"][path]
        for method in METHODS:
            op = methods.get(method)
            if not op:
                continue
            summary = op.get("summary") or op.get("operationId") or method.upper()
            out.append(f"### `{method.upper()} {path}`")
            out.append("")
            if op.get("description"):
                out.append(f"{op['description']}")
                out.append("")
            tags = op.get("tags") or []
            if tags:
                out.append(f"**Tags**: {', '.join(tags)}")
                out.append("")
            params = op.get("parameters") or []
            if params:
                out.append("| Parameter | In | Type | Required |")
                out.append("|---|---|---|---|")
                for p in params:
                    required = "yes" if p.get("required") else "no"
                    out.append(
                        f"| `{p.get('name')}` | {p.get('in')} | {type_of(p.get('schema'))} | {required} |"
                    )
                out.append("")
            body = op.get("requestBody")
            if body:
                content = body.get("content", {})
                if "application/json" in content:
                    schema = content["application/json"].get("schema", {})
                    if "$ref" in schema:
                        out.append(f"**Request body**: `{schema['$ref'].rsplit('/', 1)[-1]}`")
                    else:
                        out.append(f"**Request body**: {type_of(schema)}")
                out.append("")
            responses = op.get("responses", {})
            out.append("**Responses**: " + ", ".join(sorted(responses.keys())))
            out.append("")
            out.append("---")
            out.append("")

    schemas = spec.get("components", {}).get("schemas", {})
    if schemas:
        out.append("## Schemas")
        out.append("")
        for name in sorted(schemas):
            schema = schemas[name]
            ref = schema.get("$ref")
            if ref:
                out.append(f"- {name} → `{ref.rsplit('/', 1)[-1]}`")
            else:
                out.append(render_schema(name, schema))
            out.append("---")
            out.append("")

    sys.stdout.write("\n".join(out))


if __name__ == "__main__":
    main()
