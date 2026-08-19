# BossCrafting

Spigot plugin for forging configurable EliteBosses summoning eggs from protected custom crafting materials.

## Setup

1. Install Java 8+ and a Spigot 1.8.8/1.8.9 server.
2. Install EliteBosses.
3. Put `BossCrafting-1.2.1.jar` in the server's `plugins` folder.
4. Start the server to generate the configuration.
5. Edit `plugins/BossCrafting/config.yml`.
6. Run `/eb crafting reload`.

BossCrafting uses the EliteBosses give command to create genuine boss eggs with the metadata expected by the installed EliteBosses version.

## Commands

- `/eb craft` - open the Boss Forge when direct command access is enabled
- `/eb materials give <player> <material> [amount]` - give custom crafting materials
- `/eb crafting reload` - reload the configuration
- `/bcraft createnpc [name]` - create and configure the Citizens forge NPC

`/bosscrafting` and `/bcraft` can also be used as fallback commands. Direct craft commands are disabled by default so players must use an NPC.

Create the NPC where you are standing with one command:

`/bcraft createnpc`

BossCrafting creates the Citizens NPC with the bold purple `»BossMage«` name, applies its wizard skin, and attaches the console command automatically. The name, skin account, optional direct skin PNG URL, and attached command are configurable under `npc`.

The NPC's `bosscrafting open <player>` action is console-only. Players can click the NPC to open the forge, but cannot type that command themselves—even if they have the administrative permission. Direct `/eb craft` and `/bcraft craft` access remains controlled by `settings.craft-command-enabled`.

Default material IDs are `abyssal_scale`, `celestial_fragment`, `infernal_core`, `kraken_tentacle`, `void_shard`, and `binding_rune`.

## Permissions

- `bosscrafting.craft` - open the forge and craft boss eggs
- `bosscrafting.admin` - give crafting materials and reload the plugin

The crafting permission is enabled by default. The administrative permission defaults to server operators.

Both nodes can be changed in `config.yml`, for example:

```yaml
permissions:
  craft: 'paradise.pluginName.permission'
  admin: 'paradise.pluginName.admin'
```

## Configuration

The Boss Forge can be customized from `config.yml`.

You can change:

- GUI title, size, recipe slots, and close behavior
- Direct craft command access, disabled by default
- Custom material types, legacy data values, names, lore, and glow
- Boss icons, names, descriptions, ingredients, and required amounts
- EliteBosses plugin name and egg delivery command
- Maximum material give amount
- Plugin messages and colours

The default configuration includes recipes for `abyssal_leviathan`, `celestial-devourer`, `inferno-warden`, `kraken`, and `void-reaver` with a dark purple boss ritual theme.

## Security

- Crafting materials stack in securely registered batches of up to 64.
- Every batch has a persistent UUID and legitimate remaining amount in `item-registry.yml`.
- Material and batch IDs are stored in hidden 1.8.9 item NBT, not visible lore.
- Split stacks remain valid, but copied stacks cannot spend more than the batch's registered total.
- Crafting materials also use type signatures and exact metadata checks.
- Renamed vanilla items and visual copies are not accepted.
- Custom crafting materials cannot be placed as blocks.
- Recipe requirements are validated again when the player clicks.
- The forge blocks item movement, shift-clicking, and dragging.
- Ingredients are removed only by server-side inventory logic.
- The player's inventory is restored if the EliteBosses command cannot be dispatched.
- Give amounts are capped at a configurable limit with a hard maximum of 2304.

## Build

Run:

```powershell
.\gradlew.bat build
```

The plugin will be created at `build/libs/BossCrafting-1.2.1.jar`.

## Video

[Watch the BossCrafting demonstration video](pictures/bosscrafting.mov)
