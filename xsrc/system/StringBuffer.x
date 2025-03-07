/**
 * A StringBuffer is used to efficiently create a resulting String from any number of contributions
 * of any size.
 */
class StringBuffer
        implements Appender<Char>
        implements Sequence<Char>
        implements Stringable
    {
    /**
     * Construct a StringBuffer.
     *
     * @param capacity  an optional value indicating the expected size of the resulting String
     */
    construct(Int capacity = 0)
        {
        chars = new Array<Char>(capacity);
        }

    /**
     * The underlying representation of a StringBuffer is a mutable array of characters.
     */
    private Char[] chars;


    // ----- StringBuffer API ----------------------------------------------------------------------

    /**
     * Append a value to the StringBuffer.
     *
     * @param o  the object to append
     */
    @Op("+")
    StringBuffer append(Object o)
        {
        if (o.is(Stringable))
            {
            o.appendTo(this);
            }
        else
            {
            add(o.toString());
            }

        return this;
        }

    @Override
    String toString()
        {
        return new String(chars);
        }


    // ----- Stringable methods --------------------------------------------------------------------

    @Override
    Int estimateStringLength()
        {
        return size;
        }

    @Override
    void appendTo(Appender<Char> appender)
        {
        appender.add(chars);
        }


    // ----- Appender methods ----------------------------------------------------------------------

    @Override
    StringBuffer add(Char v)
        {
        chars[size] = v;
        return this;
        }

    @Override
    StringBuffer add(Iterable<Char> array)
        {
        chars += array;
        return this;
        }


    // ----- Sequence methods ----------------------------------------------------------------------

    @Override
    Int size.get()
        {
        return chars.size;
        }

    @Override
    @Op("[]")
    @Op Char getElement(Int index)
        {
        return chars[index];
        }

    @Override
    @Op("[]=")
    void setElement(Int index, Char value)
        {
        chars[index] = value;
        }

    @Override
    @Op("[..]")
    StringBuffer! slice(Range<Int> range)
        {
        StringBuffer that = new StringBuffer(range.size);
        that.add(chars[range]);
        return that;
        }

    @Override
    Iterator<Char> iterator()
        {
        return chars.iterator();
        }

    @Override
    conditional Int indexOf(Char value, Int startAt = 0)
        {
        return chars.indexOf(value, startAt);
        }

    @Override
    conditional Int lastIndexOf(Char value, Int startAt = Int.maxvalue)
        {
        return chars.lastIndexOf(value, startAt);
        }
    }
