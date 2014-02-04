This is an example mod, demonstrating how to implement a tile entity that can be accessed from programs on an OpenComputers computer connected to the tile entity.

**Important**: you'll need the OpenComputers API in your classpath to compile this example.

The example tile entity is a simple entity radar, that returns a list of living entities in the range of the tile entity, providing each entity's name and position. When connected to a computer it can be accessed as a component named "radar":
```
lua> =component.radar.getEntities()
{{name="Steve",x=0,z=-3}, {name="Chicken",x=-14,z=-3},n=2}
```

The mod is as minimal as possible, while still actually working, so as not to distract from the functionality it is designed to demonstrate. There are no textures, recipes or other details, only a single block with a fixed ID to allow creation of the tile entity. Feel free to base a proper addon on the code in this example.

Feel free to submit pull requests to expand and/or clarify documentation!