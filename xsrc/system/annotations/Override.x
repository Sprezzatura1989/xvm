/**
 * Override is a compile-time mixin for virtual child classes, properties, and methods:
 * * A method marked with {@code @Override} on a type that does not have a corresponding method
 *   declaration on its super-type will cause a compile-time error;
 */
mixin Override
        into Class | Property | Method
    {
    }
