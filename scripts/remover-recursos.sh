#!/bin/bash
set -euo pipefail

RESOURCE_GROUP="rg-elosolidario"

az group delete --name "$RESOURCE_GROUP" --yes --no-wait
echo "Remoção do grupo $RESOURCE_GROUP solicitada."
