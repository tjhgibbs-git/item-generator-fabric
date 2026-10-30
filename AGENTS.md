# Notes for agents

## Look at GUI changes with block-scraper

Use [block-scraper](https://github.com/tjhgibbs-git/block-scraper) to see and drive the
screens after changing them, rather than relying on the build alone. With a checkout next to
this repo (`../block-scraper`), the build gains `:fabric:runBlockScraper`:

```sh
export JAVA_HOME=~/.local/jdk/jdk-25.0.5+8 PATH=$JAVA_HOME/bin:$PATH
B=../block-scraper/block-scraper
$B start --project-dir . --task :fabric:runBlockScraper
$B world --creative
$B open class:io.github.tjhgibbs.stacksmith.client.ItemEditorScreen   # or MobEditorScreen, SpawnerEditorScreen
$B shot <scratch>/item.png        # then look at the PNG
$B widgets                        # widget positions, in GUI coordinates
$B click --text New               # or: click <x> <y> (GUI coordinates, not screenshot pixels)
$B type "Excalibur"
$B stop
```

- `$B reload` hot-swaps method bodies only; after adding fields or methods use `$B restart`.
- Widgets inside scroll panes (the text areas) don't show in `widgets`; click them by position.
  Screenshot pixels / GUI scale = GUI coordinates (scale 3 at the default 1280x720).
- `type` may hand characters straight to the screen, so it doesn't prove that real keyboard
  input works (26.3's SDL input only sends characters while a widget has asked for text input).

## Building and installing

`./gradlew build` needs `JAVA_HOME` set as above; it also runs the game tests. To try the mod
in the user's Prism instance, copy `fabric/build/libs/stacksmith-0.1.0.jar` to
`/mnt/c/Users/tjhgi/AppData/Roaming/PrismLauncher/instances/26.3/minecraft/mods/`. It must
work alongside the other mods there (Axiom, Fabric API, Fabric Language Kotlin and the
user's own mods).
