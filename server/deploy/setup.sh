#!/usr/bin/env bash
# One-time and repeatable setup of a fresh Ubuntu 24.04 VPS for the AniMix API.
# Run as root from /opt/animix (deploy.ps1 does this):  bash setup.sh
# Safe to run again: every step checks what is already done.
set -euo pipefail
cd "$(dirname "$0")"

echo "== Swap (2 GB): building the API image needs more than 1 GB of RAM"
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
sysctl -q vm.swappiness=10
grep -q '^vm.swappiness' /etc/sysctl.conf || echo 'vm.swappiness=10' >> /etc/sysctl.conf

echo "== Packages: Docker from Ubuntu, firewall, automatic security updates"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q docker.io docker-compose-v2 ufw unattended-upgrades >/dev/null
# Some provider paths silently drop full-size packets from containers (npm hung mid-download).
# A slightly smaller MTU for Docker networks avoids that black hole.
if ! grep -q '"mtu"' /etc/docker/daemon.json 2>/dev/null; then
  mkdir -p /etc/docker
  echo '{ "mtu": 1400, "log-driver": "json-file", "log-opts": { "max-size": "10m", "max-file": "3" } }' > /etc/docker/daemon.json
  systemctl restart docker
fi
systemctl enable --now docker >/dev/null

echo "== Firewall: SSH, HTTP and HTTPS only"
ufw allow 22/tcp >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw --force enable >/dev/null

echo "== SSH: keys only (the key was added in the VDSina panel)"
if [ -s /root/.ssh/authorized_keys ]; then
  sed -i -E 's/^#?PasswordAuthentication .*/PasswordAuthentication no/' /etc/ssh/sshd_config
  systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null || true
fi

echo "== AniMix: build and start"
test -f .env || { echo "Missing /opt/animix/.env (deploy.ps1 creates it)"; exit 1; }
mkdir -p backups media
# The API runs as the image's "node" user (uid 1000) and writes profile media here.
chown 1000:1000 media
docker compose up -d --build
docker image prune -f >/dev/null

echo "== Waiting for the API"
for i in $(seq 1 60); do
  if docker compose exec -T api wget -qO- http://127.0.0.1:8080/health >/dev/null 2>&1; then
    echo "API is up"; docker compose ps; exit 0
  fi
  sleep 3
done
echo "API did not start; recent logs:"; docker compose logs --tail 80 api; exit 1
