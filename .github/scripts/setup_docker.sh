#!/bin/bash
set -e

sudo apt install docker.io
sudo systemctl unmask docker

sudo mkdir /mnt/docker
#sudo echo 'DOCKER_OPTS="-dns 8.8.8.8 -dns 8.8.4.4 -g /mnt/docker"' > /etc/default/docker

sudo cat /lib/systemd/system/docker.service
sudo sh -c "sed -i 's#ExecStart=/usr/bin/dockerd -H fd://#ExecStart=/usr/bin/dockerd -g /mnt/docker -H fd://#' /lib/systemd/system/docker.service"
sudo cat /lib/systemd/system/docker.service


sudo systemctl daemon-reload
sudo rsync -aqxP /var/lib/docker/ /mnt/docker

sudo systemctl start docker

ps aux | grep -i docker | grep -v grep