#!/bin/bash
set -e

sudo apt-get install \
    apt-transport-https \
    ca-certificates \
    curl \
    gnupg-agent \
    software-properties-common

curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -

sudo add-apt-repository \
   "deb [arch=amd64] https://download.docker.com/linux/ubuntu \
   $(lsb_release -cs) \
   stable"

sudo apt-get update

sudo apt-get install docker-ce docker-ce-cli containerd.io

sudo systemctl stop docker

sudo mkdir /mnt/docker
echo 'DOCKER_OPTS="-dns 8.8.8.8 -dns 8.8.4.4 -g /mnt/docker"' > /etc/default/docker

echo "Creating symlink"

sudo ln -s /mnt/docker /var/lib/docker

sudo systemctl start docker