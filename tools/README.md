# tools

One-off generators, kept here so the binaries they produce stay reviewable
and reproducible rather than being blobs nobody can regenerate.

Run with a JDK 21 (single-file source mode, no build needed):

```bash
java tools/GenTex.java    src/main/resources/assets/skyport/textures/block
java tools/GenPonder.java src/main/resources/assets/skyport/ponder
```

- `GenTex` - the 16x16 block textures.
- `GenPonder` - the structure NBT each Ponder scene is played inside.
  Ponder structures are normally built in game and saved with a structure
  block; generating them means a scene's set can be changed in a diff.

If you edit a Ponder scene's wording, remember the text lives twice: in
`SkyportPonder` and in `en_us.json` under `skyport.ponder.<scene>.text_N`.
They must match, or the scene shows the raw key.
