## What changes and why?

<!-- Business domain / scope of this change -->

## Architecture and compatibility

- [ ] Domain logic has no framework or database dependencies.
- [ ] Controller → application → domain / ports; no SQL in controller.
- [ ] Cross-module references use an explicit contract, not private adapters.
- [ ] Legacy API / PostgreSQL behavior preserved, or difference documented.
- [ ] Privileged actions and resource ownership checked.

## Verification

- [ ] `mvn -B -ntp -f backend/pom.xml verify`
- [ ] Characterization / contract test added or marked not applicable.
- [ ] Negative authorization tests added or marked not applicable.

## Database, operations and rollout

- Schema or migration impact:
- Secrets / config impact:
- Docker / Portainer / rollback impact:

## Source safety

- [ ] No commits made to the original `waleed875/memo_project` repository.
