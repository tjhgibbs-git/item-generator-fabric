#!/usr/bin/env bash
# Prints public signatures of Minecraft / Fabric API classes from the Gradle cache, so a
# session that cannot reach Mojang's or Fabric's servers can read them in the CI log.
# Each line of the list file is one of:
#   net.minecraft.Foo            javap -public of that class
#   grep:some/path/Part          class names in the jars containing that text
#   protected:net.minecraft.Foo  javap -protected (for classes meant to be subclassed)
set -u
jars=$(find ~/.gradle/caches -name '*.jar' \( -path '*minecraft*' -o -path '*fabric-api*' -o -path '*net.fabricmc*' \) 2>/dev/null | grep -v sources | sort -u)
cp=$(echo "$jars" | tr '\n' ':')
echo "== jars searched: $(echo "$jars" | wc -l)"
while IFS= read -r line; do
  [[ -z "$line" || "$line" == \#* ]] && continue
  if [[ "$line" == grep:* ]]; then
    pat=${line#grep:}
    echo "==== classes matching $pat"
    for j in $jars; do unzip -Z1 "$j" 2>/dev/null | grep -F "$pat" | grep '\.class$' | grep -v '\$[0-9]' ; done | sort -u | head -300
  elif [[ "$line" == protected:* ]]; then
    echo "==== ${line#protected:} (protected)"
    javap -protected -cp "$cp" "${line#protected:}" 2>&1 | head -400
  else
    echo "==== $line"
    javap -public -cp "$cp" "$line" 2>&1 | head -400
  fi
done < "$1"
