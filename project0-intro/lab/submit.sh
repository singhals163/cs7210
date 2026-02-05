#!/usr/bin/env bash

MAX_SIZE_MB=20
MAX_SIZE_BYTES=$((MAX_SIZE_MB * 1024 * 1024))

if [[ "$OSTYPE" == "darwin"* ]]; then
    stat_command="stat -f %z"  # macOS
else
    stat_command="stat --format=%s"  # Linux
fi

if [ $# -eq 1 ]; then
    gt_id=$1
    zip_file="${gt_id}.zip"

    files_to_zip=("src")

    if [ -f "REPORT.pdf" ]; then
        files_to_zip+=("REPORT.pdf")
    elif [ -f "REPORT.md" ]; then
        files_to_zip+=("REPORT.md")
    else
        echo "Neither REPORT.pdf nor REPORT.md found."
        exit 1
    fi

    total_size=0
    for file in "${files_to_zip[@]}"; do
        if [ -d "$file" ]; then
            total_size=$((total_size + 1024 * $(du -sk "$file" | cut -f1)))
        else
            total_size=$((total_size + $(eval $stat_command "$file")))
        fi
    done

    if [ "$total_size" -gt "$MAX_SIZE_BYTES" ]; then
        echo "Error: Total size of the files is $(($total_size / 1024 / 1024))MB, which exceeds the $MAX_SIZE_MB MB limit."
        exit 1
    fi

    # Create the zip file with the selected files
    zip -r "$zip_file" "${files_to_zip[@]}"
else
    echo 'Please provide your GTID, e.g., ./submit.sh syi73'
fi
