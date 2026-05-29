#!/system/bin/sh

MODDIR=${0%/*}
export MODDIR

echo "ViaTabs Root Helper"
echo "Running one-shot Via DB export..."
sh "$MODDIR/common/export-via-db.sh" once

