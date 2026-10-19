# KY3P Utilities: Remediation & DDQ

Two GitHub Actions workflows that export data from Oracle, validate it, and upload it to the client SFTP.

## How to run
Repo → **Actions** → pick **KY3P Remediation Utility** or **KY3P DDQ Utility** → **Run workflow**.

### Remediation inputs
| Input | Required | Notes |
|---|---|---|
| environment | Yes | QA or PROD |
| remediation_comp_id | Yes | Letters, numbers, `_`, `-` only |
| remediation_output_filename | No | Default `Remediation-Request_List.xlsx` |
| remediation_start_date / remediation_end_date | No | `YYYY-MM-DD`. Provide both or neither |
| remediation_year | No | Optional filter |

### DDQ inputs
| Input | Required | Notes |
|---|---|---|
| environment | Yes | QA or PROD |
| ddq_user_comp_id | Yes | Letters, numbers, `_`, `-` only |
| ddq_year | No | Default 2026 |

## Flow
1. **Setup**: validates inputs, Python version and staging bucket.
2. **Execute**: pulls config from S3, exports from Oracle, produces a validation report.
   - Remediation also samples 10% of IDs and stages the export in S3.
3. **QA remediation only**: fetches the sampled IDs from the KY3P API, then compares API vs Excel.
4. **Approval** (only if needed): a reviewer approves or rejects the upload.
5. **SFTP upload**: archives, uploads, then verifies checksums.
6. **Summary**: job results table on the run page.

## When approval is required
Validation is not PASS, **or** the comparison has mismatches, **or** the fetch/compare job crashed (QA remediation).
Review the artifacts first, then approve or reject. Rejecting means no upload.
If nothing is wrong, the upload runs automatically.

## Prerequisites
- Repo secrets: `ORACLE_DSN`, `ORACLE_USER`, `ORACLE_PASSWORD`, `KY3P_API_USERNAME`, `KY3P_API_PASSWORD`
- Repo variables: `REMEDIATION_STAGING_BUCKET_QA`, `REMEDIATION_STAGING_BUCKET_PROD`
- GitHub environments with required reviewers: `remediation-upload-QA`, `remediation-upload-PROD`, `ddq-upload-QA`, `ddq-upload-PROD`
- S3 `ky3p-configuration.us-east-1.dev-ky3p.ihsmarkit.com/utilities-config/<remediation|ddq>/` contains `<compId>_SFTP_CONFIG.json` and the other config files for the company
- S3 `remediation-api/scripts/fetch_remediations_remote_new.py` is present (QA remediation)
- QA app instance tagged `ky3p-usqa-data-api-cluster-ECS` is running with SSM enabled (QA remediation)
- AWS OIDC roles exist for QA and PROD; the runner can reach Oracle and the SFTP host

## Outputs & where to find them
| What | Where |
|---|---|
| Validation report | Run summary + artifact `remediation-validation-report` / `ddq-validation-report` (30 days) |
| Comparison report | Artifact `remediation-comparison-report` (30 days) |
| API JSONs | Artifact `remediation-jsons-<run_id>` (7 days) |
| DDQ zips | Artifact `utility-archives-ddq` (7 days) |
| Overall result | Run summary → "Workflow Execution Summary" |

## Troubleshooting
| Symptom | Likely cause |
|---|---|
| Fails in Setup | Invalid company ID, one date only, or staging variable missing |
| Fails in Execute | Oracle/config issue; check the prepare/export step log |
| Waiting for approval | See "When approval is required" |
| SFTP step fails | Missing `<compId>_SFTP_CONFIG.json`, DNS failure, or port 22 blocked |
| Checksum step fails | Upload incomplete; rerun |
