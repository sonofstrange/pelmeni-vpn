#!/bin/bash
set -e

mkdir -p /etc/pelmeni-api /opt/pelmeni-api
chmod 700 /etc/pelmeni-api

cat > /etc/systemd/system/pelmeni-api.service << 'UNIT'
[Unit]
Description=Pelmeni VPN Registry API
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/opt/pelmeni-api
ExecStart=/usr/bin/python3 -m uvicorn main:app --host 0.0.0.0 --port 8765 --no-access-log
Restart=always
RestartSec=3
User=root
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable pelmeni-api.service 2>/dev/null || true
systemctl restart pelmeni-api.service
sleep 3

if systemctl is-active --quiet pelmeni-api.service; then
    echo "SERVICE_OK"
else
    journalctl -u pelmeni-api.service -n 30 --no-pager
    echo "SERVICE_FAILED"
    exit 1
fi

# Открываем порт через iptables
iptables -C INPUT -p tcp --dport 8765 -j ACCEPT 2>/dev/null || \
    iptables -I INPUT -p tcp --dport 8765 -j ACCEPT || true

# Сохраняем правила
if command -v iptables-save > /dev/null; then
    iptables-save > /etc/iptables.rules 2>/dev/null || true
fi

TOKEN=$(cat /etc/pelmeni-api/admin.token 2>/dev/null || echo "pending")
echo "ADMIN_TOKEN=${TOKEN}"
