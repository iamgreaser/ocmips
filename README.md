This is an example mod, demonstrating how to implement an architecture that can be accessed from computer cases.

To run it, after cloning the repository, set up your workspace with gradle as usual:
```
gradlew setupDecompWorkspace idea
```
I recommend enabling the Gradle plugin in IDEA. When opening the project in IDEA with it enabled, it will ask you whether you'd like to import the Gradle project. When you do so, it'll automatically set up the library dependency on the OC API for you.

The example architecture is a simple functionless architecture, that runs but does nothing.

The mod is as minimal as possible, while still actually working, so as not to distract from the functionality it is designed to demonstrate. There are no textures, recipes or other details, only a simple architecture. Feel free to base a proper addon on the code in this example.

Feel free to submit pull requests to expand and/or clarify documentation!
