# Free public deployment

This is a small **personal portfolio deployment**, not a claim of production uptime. The local application and tests already work. The files in `infra/public-demo/` run the whole app on one Linux VM: PostgreSQL with pgvector, Redis, Java API, React frontend, and Caddy HTTPS. Only ports 80 and 443 are published by Compose. AI workers stay off, so there are no paid model calls.

## Free resources and real limits

- **Compute:** An [Oracle Cloud Always Free Ampere A1 VM](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm) can provide up to **2 OCPUs and 12 GB RAM** under the current monthly allowance, with free block storage. Select an Always Free eligible shape and stay inside the displayed limits. Oracle may lack capacity in your region and may reclaim an idle VM. Account creation commonly requires a card and phone, even when usage remains free.
- **Hostname:** [DuckDNS](https://www.duckdns.org/faqs.jsp) offers a free subdomain. Point its A record at the VM's public IPv4 address. Update it if the address changes.
- **HTTPS:** [Caddy](https://caddyserver.com/docs/automatic-https) obtains and renews certificates when DNS points to the VM and ports 80/443 are reachable.
- **Email:** Verification and password reset need working SMTP. A dedicated Gmail account with 2-Step Verification and an [app password](https://support.google.com/accounts/answer/185833) can be used for a small personal demo, although Google discourages app passwords and may not offer them on every account. Another SMTP provider works if it permits your sender address. [Resend's free plan](https://resend.com/pricing) has 3,000 emails/month and 100/day, but sending to arbitrary addresses requires a verified domain you control; a DuckDNS hostname alone is not enough for that setup. Never use your main personal account password.
- **AI:** Leave `AI_WORKER_ENABLED` and `AI_EMBEDDING_WORKER_ENABLED` off. The core incident workflow works without a model provider.

Free plans and availability can change. Check the linked provider pages before creating resources. This guide does not require a paid domain, AI key, managed database, or managed Redis.

If an Always Free VM is unavailable in your region, the [narrated walkthrough](demo/error-intelligence-walkthrough.webm), source code, and [local user guide](user-guide.md) are still valid CV evidence. Do not pay for a host just to make this small project public.

## 1. Prepare the VM

Create an Always Free eligible **Ubuntu ARM64** VM with 2 OCPUs, 12 GB RAM, and a boot volume within your free storage allowance. Assign a public IPv4 address. In Oracle's network security rules and the VM firewall, allow inbound TCP **22** from your own IP and TCP **80/443** from the internet. Keep PostgreSQL, Redis, and backend ports closed publicly.

Install [Docker Engine and its Compose plugin for Ubuntu](https://docs.docker.com/engine/install/ubuntu/) using Docker's official instructions. Check `docker compose version`. The deployment scripts require Bash, curl, OpenSSL, and Docker Compose. Docker group membership grants root-level control of the host; either use a dedicated VM login with that access or run the scripts through `sudo`.

Copy this repository to the VM, preferably through a Git remote. If you have not published it yet, transfer a source archive that excludes `.env`, `.local`, `.tools`, `node_modules`, `target`, and build outputs. Keep the deployment environment file **only on the VM**.

## 2. Point DNS and configure email

Create a DuckDNS name, for example `your-name.duckdns.org`, and point it at the VM's public IP. On the VM, from the repository root:

```bash
bash infra/public-demo/init-env.sh your-name.duckdns.org
```

That creates `infra/public-demo/public-demo.env` with random PostgreSQL, Redis, and JWT signing secrets and permissions `600`. It refuses to overwrite existing secrets. Edit the file and fill `SMTP_HOST`, `SMTP_USERNAME`, `SMTP_PASSWORD`, and `MAIL_FROM`. For a dedicated Gmail account, use `smtp.gmail.com`, port `587`, its address as username and sender, and its separate app password. Do not commit this file or paste its contents into an issue or CV.

## 3. Deploy and verify

From the VM's repository root:

```bash
bash infra/public-demo/deploy.sh
```

The script checks the Compose configuration, builds the images, waits for container health, and probes the public HTTPS readiness endpoint. Caddy may need a minute for its first certificate. Then, from your own computer:

```powershell
node scripts/verify-public-demo.mjs https://your-name.duckdns.org
```

The public smoke checks HTTPS health, the Java API, the sign-in page, frontend security headers, and that the Prometheus endpoint is not public. Complete the following real-browser checks yourself because they require your mailbox and private ingestion key:

1. Register with **your own test email**, receive the link, verify, and sign in.
2. Request a password reset, receive that link, set a new password, and confirm the old password fails.
3. Create a project and ingestion key. Store the full key in a password manager; it is shown once.
4. Send a **synthetic** event with the command in the [user guide](user-guide.md) after changing its URL to your HTTPS domain. Confirm the incident and analytics appear.
5. Revoke the key and confirm further ingestion with that key fails.

Do not put a live ingestion key in frontend code or a public CV. A Java application must send its own error events; the browser does not read application logs automatically. For an ongoing Java integration, use the [small server-side sender example](../examples/java-sender/README.md).

## 4. Back up and update

Before every update, from the VM's repository root:

```bash
bash infra/public-demo/backup.sh
```

The script creates a private PostgreSQL custom-format dump in the ignored `infra/public-demo/backups/` directory and verifies that its archive can be listed. **Download a copy to another computer**; backups on the same VM do not survive a VM disk loss. Then update the source and run `bash infra/public-demo/deploy.sh` again. Keep the previous Git commit or source archive so you can rebuild it if the new app fails. Do not reverse Flyway migrations during an ordinary code rollback.

To rehearse a real restore without touching the live application database, run `bash infra/public-demo/verify-backup.sh infra/public-demo/backups/<your-backup>.dump`. It restores into a temporary database, checks Flyway history, then removes that temporary database. Keep enough free disk space for a second copy during this check.

On a **new VM with an empty database volume**, copy back the private environment file and backup, start only `postgres` and `redis` with Compose, then restore the dump before starting the other services:

```bash
docker compose --env-file infra/public-demo/public-demo.env -f infra/public-demo/compose.yml up -d postgres redis
docker compose --env-file infra/public-demo/public-demo.env -f infra/public-demo/compose.yml exec -T postgres \
  pg_restore --exit-on-error --no-owner --no-acl -U error_intelligence -d error_intelligence \
  < infra/public-demo/backups/<your-backup>.dump
bash infra/public-demo/deploy.sh
```

Use that restore command only for a fresh, empty database; it is not an in-place rollback procedure.

If the VM is reclaimed, recreate it from the repository, the saved private environment file, and an off-VM database backup. Do not share the environment file or backup publicly.

## Status and CV wording

The deployment package is ready for a VM, but **a live deployment is complete only after you provide a VM, hostname, SMTP account, and pass the public smoke and mailbox tests**. Until then, describe the project as a tested Java full-stack application with a reproducible public demo deployment, not as already hosted. Once it is live, add its HTTPS URL to your CV and README.
