#!/bin/bash

# Convert all .dot files in current directory to .svg
for dot_file in *.dot; do
    if [ -f "$dot_file" ]; then
        svg_file="${dot_file%.dot}.svg"
        echo "Converting $dot_file to $svg_file"
        dot -Tsvg "$dot_file" -o "$svg_file"
    fi
done

echo "Conversion complete!"