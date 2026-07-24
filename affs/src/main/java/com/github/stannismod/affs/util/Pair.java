package com.github.stannismod.affs.util;

import javax.annotation.concurrent.Immutable;
import java.util.Objects;

@Immutable
public class Pair<T1, T2> {

    private final T1 first;
    private final T2 second;

    private Pair(T1 first, T2 second) {
        this.first = first;
        this.second = second;
    }

    public static <T1, T2> Pair<T1, T2> of(T1 Item1, T2 Item2) {
        return new Pair<T1,T2>(Item1, Item2);
    }

    public T1 getFirst() {
        return this.first;
    }

    public T2 getSecond() {
        return this.second;
    }

    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof Pair)) return false;
        final Pair other = (Pair) o;
        final Object this$Item1 = this.getFirst();
        final Object other$Item1 = other.getFirst();
        if (!Objects.equals(this$Item1, other$Item1)) return false;
        final Object this$Item2 = this.getSecond();
        final Object other$Item2 = other.getSecond();
        return Objects.equals(this$Item2, other$Item2);
    }

    public int hashCode() {
        final int PRIME = 59;
        int result = 1;
        final Object $Item1 = this.getFirst();
        result = result * PRIME + ($Item1 == null ? 43 : $Item1.hashCode());
        final Object $Item2 = this.getSecond();
        result = result * PRIME + ($Item2 == null ? 43 : $Item2.hashCode());
        return result;
    }

    public String toString() {
        return "Pair(Item1=" + this.getFirst() + ", Item2=" + this.getSecond() + ")";
    }
}
