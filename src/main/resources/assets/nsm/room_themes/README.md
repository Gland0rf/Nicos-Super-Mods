# NSM room themes

`index.json` is the only registry. Add a scene JSON path to its `scenes` array
and the mod will load it at startup. No Java registration is required.

If several scenes match the player at once, the scene with the highest
`priority` is used. This also lets an alternate theme override a default theme.

## Activation

Each scene can filter on:

- `requiresSkyBlock`
- `areas` using `LocationUtils.Island` names, such as `DUNGEON`, `HUB`, or `THE_END`
- `floors`, which may contain multiple dungeon floor numbers
- `boss`, which may be `true`, `false`, or omitted
- `masterMode`: `ANY`, `MASTER`, or `NORMAL`
- world-coordinate `min` and `max` bounds

An empty or omitted `areas`/`floors` array matches any area/floor.

## Geometry

- `sky` is optional and contains depth-tested color bands.
- `cuboids` are absolute world-space colored boxes.
- `templates` contain reusable local-space cuboid parts.
- `instances` place and scale a template in world space.
- `animations` are bounded falling/swaying cuboids for leaves, snow, sparks,
  dust, or similar lightweight effects.

Every part, instance, cuboid, and animation can specify `minimumDetail`:

- `0`: Low, Balanced, and High
- `1`: Balanced and High
- `2`: High only

Colors use normalized RGB or RGBA arrays, for example `[0.2, 0.6, 0.3]`.
All geometry remains client-side and depth-tested; scene JSON never changes
world blocks, collision, packets, camera perspective, or block transparency.