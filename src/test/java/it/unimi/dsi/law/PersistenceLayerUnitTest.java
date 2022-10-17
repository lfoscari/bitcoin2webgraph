package it.unimi.dsi.law;

import it.unimi.dsi.law.persistence.*;
import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.apache.commons.collections4.multimap.HashSetValuedHashMap;
import org.assertj.core.util.Files;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.TransactionOutPoint;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rocksdb.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PersistenceLayerUnitTest {
    @Mock TransactionOutPoint top_a, top_b, top_c;
    @Mock Address addr_a, addr_b, addr_c;

    static PersistenceLayer pl;
    static IncompleteMappings im;
    static AddressConversion ac;
    static TransactionOutpointFilter tof;

    static RocksDB db;
    static List<ColumnFamilyHandle> columns;

    @BeforeAll
    static void setup() throws RocksDBException, NoSuchFieldException, IllegalAccessException, IOException {
        File temp = Files.newTemporaryFolder();
        temp.deleteOnExit();

        pl = PersistenceLayer.getInstance(temp.getAbsolutePath());

        Field fdb = PersistenceLayer.class.getDeclaredField("db");
        fdb.setAccessible(true);
        db = (RocksDB) fdb.get(pl);

        im = pl.getIncompleteMappings();
        ac = pl.getAddressConversion();
        tof = pl.getTransactionOutpointFilter();

        columns = List.of(
            (ColumnFamilyHandle) extract(ac, "column"),
            (ColumnFamilyHandle) extract(im, "column"),
            (ColumnFamilyHandle) extract(tof, "column")
        );
    }

    @ParameterizedTest
    @MethodSource("provideLongs")
    void convertFromLong(long l) {
        byte[] bb = ByteConversion.long2bytes(l);
        assertThat(ByteConversion.bytes2long(bb)).isEqualTo(l);
    }

    @ParameterizedTest
    @MethodSource("provideByteArrays")
    void convertFromBytes(byte[] bb) {
        long l = ByteConversion.bytes2long(bb);
        assertThat(ByteConversion.trim(ByteConversion.long2bytes(l))).containsExactly(bb);
    }

    @ParameterizedTest
    @MethodSource("provideLongList")
    void convertFromLongList(List<Long> ll) {
        byte[] bb = ByteConversion.longList2bytes(ll);
        assertThat(ByteConversion.bytes2longList(bb)).isEqualTo(ll);
    }

    @ParameterizedTest
    @MethodSource("provideByteList")
    void convertFromByteList(byte[] bb) {
        List<Long> ll = ByteConversion.bytes2longList(bb);
        assertThat(ByteConversion.longList2bytes(ll)).isEqualTo(bb);
    }

    @ParameterizedTest
    @MethodSource("provideByteArrays")
    void concatenationByteArray(byte[] bb) {
        byte[] bba = Arrays.copyOfRange(bb, 0, bb.length / 2);
        byte[] bbb = Arrays.copyOfRange(bb, bb.length / 2, bb.length);

        assertThat(ByteConversion.concat(bba, bbb)).isEqualTo(bb);
    }

    @ParameterizedTest
    @MethodSource("provideLongList")
    void putIncompleteMapping(List<Long> ll) throws RocksDBException {
        im.put(top_a, ll);
        assertThat(im.get(top_a)).containsExactlyElementsOf(ll);
    }

    @ParameterizedTest
    @MethodSource("provideLongList")
    void multiplePutIncompleteMapping(List<Long> ll) throws RocksDBException {
        assertThat(im.get(top_a)).isNullOrEmpty();

        List<Long> ll1 = ll.subList(0, ll.size() / 2);
        List<Long> ll2 = ll.subList(ll.size() / 2, ll.size());

        im.put(top_a, ll1);
        im.put(top_a, ll2);

        assertThat(im.get(top_a)).containsExactlyInAnyOrder(ll.toArray(new Long[0]));
    }

    @ParameterizedTest
    @MethodSource("provideLongList")
    void multipleEmptyPutIncompleteMapping(List<Long> ll) throws RocksDBException {
        assertThat(im.get(top_a)).isNullOrEmpty();

        im.put(top_a, ll);
        im.put(top_a, List.of());
        im.put(top_a, List.of());
        im.put(top_a, List.of());

        assertThat(im.get(top_a)).containsExactly(ll.toArray(new Long[0]));
    }

    @ParameterizedTest
    @MethodSource("provideIntList")
    void consistencyAddressConversion(List<Integer> ii) throws RocksDBException {
        // Ci sono problemi a ripulire il DB!

        Set<Long> ids = new HashSet<>();
        for (int i : ii) {
            when(addr_a.getHash()).thenReturn(intToHash(i).getBytes());
            // System.out.println(i + " -> " + intToHash(i) + " -> " + Arrays.toString(intToHash(i).getBytes()));
            ids.add(ac.mapAddress(addr_a));
        }

        assertThat(ac).extracting("count")
                .isEqualTo((long) ids.size())
                .isEqualTo(ii.stream().distinct().count());
    }

    @Test
    void consistencySingleAddressConversion() throws RocksDBException {
        when(addr_a.getHash()).thenReturn(intToHash(35).getBytes());

        long ai = ac.mapAddress(addr_a);
        assertThat(ai).isEqualTo(ac.mapAddress(addr_a));
    }

    @Test
    void multipleAddressConversion() throws RocksDBException {
        when(addr_a.getHash()).thenReturn(intToHash(35).getBytes());

        long ai = ac.mapAddress(addr_a);
        assertThat(ai).isEqualTo(0L);
    }

    @ParameterizedTest
    @MethodSource("provideLongList")
    void rocksDbWorksLikeMultiValuedMap(List<Long> ll) throws RocksDBException {
        MultiValuedMap<TransactionOutPoint, Long> mvm = new HashSetValuedHashMap<>();
        mvm.putAll(top_a, ll);
        mvm.putAll(top_b, ll);
        mvm.putAll(top_c, ll);

        im.put(top_a, ll);
        im.put(top_b, ll);
        im.put(top_c, ll);

        assertThat(im.get(top_a)).containsExactly(ll.toArray(new Long[0]));
        assertThat(im.get(top_b)).containsExactly(ll.toArray(new Long[0]));
        assertThat(im.get(top_c)).containsExactly(ll.toArray(new Long[0]));
    }

    @ParameterizedTest
    @MethodSource("provideLongList")
    void rocksDbWorksLikeMultiValuedMap2(List<Long> ll) throws RocksDBException {
        MultiValuedMap<TransactionOutPoint, Long> mvm = new HashSetValuedHashMap<>();
        mvm.putAll(top_a, ll);
        mvm.putAll(top_a, ll);
        mvm.putAll(top_a, ll);

        im.put(top_a, ll);
        im.put(top_a, ll);
        im.put(top_a, ll);

        List<Long> lll = new ArrayList<>(ll);
        lll.addAll(ll);
        lll.addAll(ll);

        assertThat(im.get(top_a)).containsExactly(lll.toArray(new Long[0]));
    }

    @ParameterizedTest
    @MethodSource("provideLongListLongListLongList")
    void rocksDbWorksLikeMultiValuedMap3(List<List<Long>> ll) throws RocksDBException {
        List<Long> l1 = ll.get(0), l2 = ll.get(1), l3 = ll.get(2);

        MultiValuedMap<TransactionOutPoint, Long> mvm = new ArrayListValuedHashMap<>();
        mvm.putAll(top_a, l1);
        mvm.putAll(top_b, l2);
        mvm.putAll(top_c, l3);

        im.put(top_a, l1);
        im.put(top_b, l2);
        im.put(top_c, l3);

        System.out.println(im.get(top_a));
        System.out.println(mvm.get(top_a));

        assertThat(im.get(top_a)).containsExactlyInAnyOrder(mvm.get(top_a).toArray(new Long[0]));
        assertThat(im.get(top_b)).containsExactlyInAnyOrder(mvm.get(top_b).toArray(new Long[0]));
        assertThat(im.get(top_c)).containsExactlyInAnyOrder(mvm.get(top_c).toArray(new Long[0]));
    }

    @ParameterizedTest
    @MethodSource("provideLongList")
    void distinctLongList(List<Long> ll) throws RocksDBException {
        List<Long> ll_mut = new ArrayList<>(ll);
        ll_mut.add(null);

        im.put(top_a, ll_mut);
        assertThat(im.get(top_a).stream().filter(Objects::nonNull).distinct().toArray())
                .containsExactlyInAnyOrder(ll_mut.stream().filter(Objects::nonNull).distinct().toArray());
    }

    @AfterEach
    void cleanup() throws RocksDBException {
        for (ColumnFamilyHandle column : columns)
            for(RocksIterator it = db.newIterator(column); it.isValid(); it.next())
                db.delete(column, it.key());

        ac.count = 0;
    }

    @AfterAll
    static void teardown() {
        pl.close();
    }

    private static Sha256Hash intToHash(int n) {
        return Sha256Hash.wrap(Arrays.copyOf(Integer.toString(n).getBytes(), 256 / 8));
    }

    private static Stream<Long> provideLongs() {
        return Stream.of(4L, 5L, 6L, 7L, 1L, 0L, -4L, -100L, Long.MAX_VALUE, Long.MIN_VALUE);
    }

    private static Stream<List<Integer>> provideIntList() {
        return Stream.of(
                List.of(1, 2, -3, 4, 5),
                List.of(),
                List.of(10),
                List.of(0, -5, 4, Integer.MIN_VALUE),
                List.of(Integer.MAX_VALUE, 1000, 2),
                List.of(1, -4, 3, 4, 4)
        );
    }

    private static Stream<byte[]> provideByteArrays() {
        return Stream.of(
                new byte[] {0},
                new byte[] {4, 5, 6, 1},
                new byte[] {Byte.MAX_VALUE, Byte.MIN_VALUE},
                new byte[] {-1, -5, 26, -127}
        );
    }

    private static Stream<List<Long>> provideLongList() {
        return Stream.of(
                List.of(1L, 2L, 3L, 4L, 5L),
                List.of(),
                List.of(10L),
                List.of(0L, 5L),
                List.of(Long.MAX_VALUE, 1000L, 2L),
                List.of(1L, 4L, 3L, 4L, 4L)
        );
    }

    private static Stream<List<List<Long>>> provideLongListLongListLongList() {
        return Stream.of(
                List.of(List.of(1L, 2L, 3L, 4L, 5L), List.of(6L, 7L, 8L, 9L, 10L), List.of(11L, 12L, 13L, 14L, 15L)),
                List.of(List.of(1L, 2L, 3L, 4L), List.of(), List.of(14L, 14L, 14L))
        );
    }

    private static Stream<byte[]> provideByteList() {
        return Stream.of(
                ByteConversion.longList2bytes(List.of()),
                ByteConversion.longList2bytes(List.of(1L, 2L, 3L, 4L, 5L, 3L, 4L, 5L)),
                ByteConversion.longList2bytes(List.of(0L, -5L, 0L, -5L, 0L, -5L, 0L, -5L)),
                ByteConversion.longList2bytes(List.of(-1000L, -1L, -1000L, -1L, -1000L, -1L, -1000L, -1L)),
                ByteConversion.longList2bytes(List.of(Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE,
                        Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE))
        );
    }

    private static Object extract(Object src, String fld) throws NoSuchFieldException, IllegalAccessException {
        Field f = src.getClass().getDeclaredField(fld);
        f.setAccessible(true);
        return f.get(src);
    }
}
