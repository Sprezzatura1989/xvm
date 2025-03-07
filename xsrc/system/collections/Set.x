/**
 * A Set is a container data structure that represents a group of _distinct values_. While the Set's
 * interface is identical to that of the Collection, its default behavior is subtly different.
 */
interface Set<ElementType>
        extends Collection<ElementType>
    {
    // ----- read operations -----------------------------------------------------------------------

    /**
     * A Set is always composed of distinct values.
     */
    @Override
    @RO Boolean distinct.get()
        {
        return true;
        }

    /**
     * The "union" operator.
     */
    @Override
    @Op("|")
    Set addAll(Iterable<ElementType> values);

    /**
     * The "relative complement" operator.
     */
    @Override
    @Op("-")
    Set removeAll(Iterable<ElementType> values);

    /**
     * The "intersection" operator.
     */
    @Override
    @Op("&")
    Set retainAll(Iterable<ElementType> values);

    /**
     * The "symmetric difference" operator determines the elements that are present in only this
     * set or the othet set, but not both.
     *
     *   A ^ B = (A - B) | (B - A)
     *
     * A `Mutable` set will perform the operation in place; persistent sets will return a new set
     * that reflects the requested changes.
     *
     * @param values  another set containing values to determine the symmetric difference with
     *
     * @return the resultant set, which is the same as `this` for a mutable set
     */
    @Op("^")
    Set symmetricDifference(Set!<ElementType> values)
        {
        ElementType[]? remove = null;
        for (ElementType value : this)
            {
            if (values.contains(value))
                {
                remove = (remove ?: new ElementType[]) + value;
                }
            }

        ElementType[]? add = null;
        for (ElementType value : values)
            {
            if (!this.contains(value))
                {
                add = (add ?: new ElementType[]) + value;
                }
            }

        Set<ElementType> result = this;
        result -= remove?;
        result |= add?;
        return result;
        }

    /**
     * The "complement" operator.
     *
     * @return a new set that represents the complement of this set
     *
     * @throws UnsupportedOperation  if this set is incapable of determining its complement, which
     *                               may be a reflection of a limitation of the ElementType itself
     */
    @Op("~")
    Set! complement()
        {
        // TODO default implementation should just create a Set that answers the opposite of what
        //      this set answers for all the "contains" etc. operations
        throw new UnsupportedOperation();
        }
    }
