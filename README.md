MIPS for OpenComputers. 'Nuff said.

Consult MemoryMap.txt for more info on how to code for this damn thing.

This comes with an ELF bootloader!
Set your start address to somewhere after the first 20KB in unmapped space
(`-Wl,-Ttext-segment=0xA0005000` works fine), and rock on!

Note, the bootloader will let you use the user space as it has an identity paging handler,
but this is not recommended.

Nevertheless, I have a working Lua 5.3 implementation that's been compiled
to test this facility.

Currently, the place to put your preferred OS kernel is:

```
src/main/resources/assets/ocmips/lua53/init.elf
```

For now, ask on #oc if you want the latest Lua 5.3.2 build.
Otherwise, `src/main/resources/labour.c` is a useful library.

(TODO: make bootloader not load entire file into memory before parsing)

----

Old readme with a lot of bits edited out:

To run it, after cloning the repository, set up your workspace with gradle as usual:
```
gradlew setupDecompWorkspace idea
```
I recommend enabling the Gradle plugin in IDEA. When opening the project in IDEA with it enabled, it will ask you whether you'd like to import the Gradle project. When you do so, it'll automatically set up the library dependency on the OC API for you.
