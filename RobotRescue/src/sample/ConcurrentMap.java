package sample;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

public interface ConcurrentMap<K, V> extends Map<K, V> {

    V getOrDefault(Object key, V defaultValue);

    void forEach(BiConsumer<? super K, ? super V> action);

    V putIfAbsent(K key, V value);

    boolean remove(Object key, Object value);

    boolean replace(K key, V oldValue, V newValue);

    void replaceAll(BiFunction<? super K, ? super V, ? extends V> function);

    V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction);

    V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction);

    V compute(K key,BiFunction<? super K, ? super V, ? extends V> remappingFunction);

    V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction);

}
