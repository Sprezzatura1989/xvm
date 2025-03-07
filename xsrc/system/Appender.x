/**
 * Represents the ability to append elements of some type to some structure. Specifically:
 *
 * * The elements are typed;
 * * The elements are being "added to" (appended to) this object; and
 * * The structure may be able to optimize its implementation if it gets warning in advance about
 *   expected capacity demands.
 *
 * The interface is designed so that only one method add(ElementType) needs to be implemented.
 */
interface Appender<ElementType>
    {
    /**
     * Append the specified value.
     *
     * @param v  the value to append
     *
     * @return this
     */
    Appender add(ElementType v);

    /**
     * Add the items from the passed Iterable container to this structure.
     *
     * @param iterable  the Iterable container of values to add
     *
     * @return this
     */
    Appender add(Iterable<ElementType> iterable)
        {
        ensureCapacity(iterable.size);
        add(iterable.iterator());
        return this;
        }

    /**
     * Add the items from the passed Iterator to this structure.
     *
     * @param iter  the Iterator of values to add
     *
     * @return this
     */
    Appender add(Iterator<ElementType> iter)
        {
        while (ElementType v := iter.next())
            {
            add(v);
            }
        return this;
        }

    /**
     * Indicate to the Appender that a certain number of additional elements are likely to be
     * appended.
     *
     * This allows an Appender to size buffers appropriately, for example. An invocation of this
     * method should never result in the Appender _reducing_ its capacity.
     *
     * @param count  an indicator of an expected required capacity beyond the amount *utilized* thus
     *               far
     *
     * @return this
     */
    Appender ensureCapacity(Int count)
        {
        return this;
        }
    }
