# Container editing and laptop handoff

The Chests tab is a parent editor for 27-slot physical containers: normal/trapped
chests, barrels, and all shulker colours. A double chest is indexed as two halves;
edit each half separately. Select a slot, then **Edit item** for the existing full
item editor, or **Open shulker** to edit its inventory. Nested editors keep a copy;
Back/Cancel discards it, Use/Keep returns it to its parent. Conversion to a shulker
is blocked while a shulker item remains inside. Item names, lore and other
components survive nested contents edits.

**Find in world → Index world** scans saved region files across dimensions in
singleplayer, then overlays current loaded server chunks. It never generates or
force-loads terrain. Search matches type, item IDs/data, dimension and coordinates.
On remote servers only loaded chunks can be indexed; /data permission is needed
for contents. Re-index after exploring or changing the world. The index is a
session snapshot, not a background tracking service. Unsupported/external chunk
compression and unread queries are counted in the status, rather than presented
as empty inventories. The region reader supports vanilla gzip, zlib and uncompressed
chunks. Saved data is read without data-fixing older chunk formats.

World results offer Edit, Teleport and Star. Edit reloads live data and requires
the container to be loaded in the current dimension. Teleport can cross dimensions
and places the player above the container; surroundings may be unsafe. J while
looking at a supported container opens it directly when contents queries are
available. Reopening the same linked container retains the current draft.

**Starred** stores named reusable contents as `setblock ~ ~ ~` commands, with no
world or coordinate metadata. Load always starts an unlinked container. Spawner
stars now use that same relative command representation; old SNBT spawner presets
remain readable. Loading a spawner from its library also unlinks it. Recent
spawner history still records where an operation happened.

Writes ask for a second click, build the payload in command storage, clear the
old inventory to prevent item drops, replace the block, and merge the payload.
Every command is checked against the chat length limit before anything is sent.
The queue reports commands sent, not server acknowledgement: reload to verify.
Commands are not transactional. Facing and double-chest state are retained when
editing the same block type; converting type uses that type's default state.
New placement requires a replaceable block at the player's feet. Loot-table
chests must be opened in-world and reloaded before their contents can be edited.
Take item (creative) and Copy /setblock also support large containers.

## Laptop checks

- Check the grid and action row at GUI scales 2–4 and narrow window sizes.
- Exercise real keyboard input in the type picker and library searches.
- Edit both halves of a double chest and verify facing/merging after saves.
- Index a large existing world, teleport to an unloaded chest, wait for chunks,
  then Edit; confirm current contents override the saved snapshot.
- Exercise remote operator queries, timeouts, disconnects and dimension changes.
- Try named/enchanted items and named shulkers through several nested edits.
- Verify old spawner stars load and new ones remain unlinked after reopening.
