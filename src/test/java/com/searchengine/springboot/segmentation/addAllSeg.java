
package com.searchengine.springboot.segmentation;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import com.searchengine.common.SegResult;
import com.searchengine.dao.RecordDao;
import com.searchengine.dao.RecordSegDao;
import com.searchengine.dao.SegmentationDao;
import com.searchengine.dao.TDao;
import com.searchengine.entity.Record;
import com.searchengine.entity.RecordSeg;
import com.searchengine.entity.Segmentation;
import com.searchengine.entity.T;
import com.searchengine.service.RecordService;
import com.searchengine.service.SegmentationService;
import com.searchengine.service.TService;
import com.searchengine.utils.jieba.keyword.Keyword;
import com.searchengine.utils.jieba.keyword.TFIDFAnalyzer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.*;


/**
 * 扫描data表把所有内容分词并加入分词库
 */
@SpringBootTest
public class addAllSeg {

    @Autowired
    private RecordService recordService;
    @Autowired
    private SegmentationService segmentationService;
    @Autowired
    private SegmentationDao segmentationDao;
    @Autowired
    private RecordSegDao recordSegDao;
    @Autowired
    private RecordDao recordDao;
    @Autowired
    private TDao tDao;

    TFIDFAnalyzer tfidfAnalyzer=new TFIDFAnalyzer();
    JiebaSegmenter jiebaSegmenter = new JiebaSegmenter();

    static HashSet<String> stopWordsSet;


    @Test
    public void addAllSeg(){

        //-----------------初始化-------------
        List<Record> records = recordService.queryAllRecord();
        List<Segmentation> segmentations = segmentationService.queryAllSeg();

        BloomFilter<String> bf = BloomFilter.create(Funnels.stringFunnel(Charset.forName("UTF-8")),10000000);

        if(stopWordsSet==null) {
            stopWordsSet=new HashSet<>();
            loadStopWords(stopWordsSet, this.getClass().getResourceAsStream("/jieba/stop_words.txt"));
        }

        for (Segmentation seg : segmentations) {
            bf.put(seg.getWord());
        }
        //----------------初始化结束---------------


        //----------------开始加词-----------------
        for (int loop=0;loop<75;loop++) {

            List<String> segs = new ArrayList<>(10000);
            List<RecordSeg> relations = new ArrayList<>(10000);

            int segMaxId = segmentationDao.getMaxId();  // 获取seg表中最大的id

            for (int i = loop*10000; i < (loop+1)*10000; i++) {  // 10000 15s
                Record record = records.get(i);
                String caption = record.getCaption();
                List<SegToken> segTokens = jiebaSegmenter.process(caption, JiebaSegmenter.SegMode.INDEX);
                List<Keyword> keywords = tfidfAnalyzer.analyze(caption,5);
                Map<String,RecordSeg> countMap = new HashMap<>();
                for (SegToken segToken : segTokens) {
                    String word = segToken.word;
                    if (stopWordsSet.contains(word)) continue;//判断是否是停用词
                    int segId = 0;
                    boolean exist = false;
                    if (!bf.mightContain(word)) {  // 不存在是一定不存在
                        bf.put(word);
                        segs.add(word);
                        segId = ++segMaxId;
                        segmentations.add(new Segmentation(segMaxId, word));
                    } else {  // 但是存在不一定是真的存在，但是这种误报的可能性很小，所以这时全部遍历的时间开销是完全可以接受的。
                              // https://www.geeksforgeeks.org/bloom-filter-in-java-with-examples/ 误报概率参考，1千万分之一
                        // 需要检查一下是不是真的存在
                        for (Segmentation seg : segmentations) {
                            if (word.equals(seg.getWord())) {
                                segId = seg.getId();
                                exist = true;
                                break;
                            }
                        }
                        if (!exist) {  // 和上面的操作相同
                            bf.put(word);
                            segs.add(word);
                            segId = ++segMaxId;
                            segmentations.add(new Segmentation(segMaxId, word));
                        }
                    }

                    int dataId = record.getId();
                    double tf = 0;
                    for (Keyword v : keywords) {
                        if (v.getName().equals(word)) {
                            tf = v.getTfidfvalue();
                            break;
                        }
                    }
                    //--------------计数--------------
                    if (!countMap.containsKey(word)){
                        int count = 1;
                        countMap.put(word,new RecordSeg(dataId, segId, tf, count));
                    }else {
                        RecordSeg t = countMap.get(word);
                        int count = t.getCount();
                        t.setCount(++count);
                        countMap.put(word,t);
                    }
                    //--------------------------------
                }
                for (RecordSeg t : countMap.values()) {
                    relations.add(t);
                }
            }

            segmentationDao.insertBatchSeg(segs);
            recordSegDao.insertBatch(relations);
        }

    }

    @Test
    /**
     * @author: optimjie
     * @description: 先单纯的添加分词表，为关系表的建立做准备
     * @date: 2022-05-23 10:53
     */
    public void addSegs() {
        // List<Record> records = recordService.queryAllRecord();
        List<String> segs = new ArrayList<>();
        //10000000
        BloomFilter<String> bf = BloomFilter.create(Funnels.stringFunnel(Charset.forName("UTF-8")),10000000);
        if (stopWordsSet == null) {
            stopWordsSet = new HashSet<>();
            loadStopWords(stopWordsSet, this.getClass().getResourceAsStream("/jieba/stop_words.txt"));
            System.out.println(stopWordsSet);
        }
        //300
        for (int loop = 0; loop < 300; loop++) {
            //10000
            // select * from data limit #{limit} offset #{offset}
            List<Record> records = recordService.selectPartialRecords(1000, Math.max(0, loop * 1000));
            if ((loop % 10 == 0 || loop % 10 == 5)  && loop != 0) {  // 这里注意loop应该不等于起始值，不一定非是0，因为起始值会空的，先这样写着。
                tDao.insert1(segs);
                System.out.println("===="+loop);
                segs.clear();
            }
            for (int i = loop * 1000; i < (loop + 1) * 1000; i++) {
                Record record = records.get(i % 1000);
                String caption = record.getCaption();

                List<SegToken> segTokens = jiebaSegmenter.process(caption, JiebaSegmenter.SegMode.INDEX);

                for (SegToken segToken : segTokens) {
                    String word = segToken.word;
                    if (stopWordsSet.contains(word)) continue; // 判断是否是停用词

                    /**
                     * Returns true if the element might have been put in this Bloom filter
                     *         false if this is definitely not the case.
                     * 如果元素可能已放入此Bloom筛选器，则返回true；如果确实不是这样，则返回false
                     * */
                    if (!bf.mightContain(word)) {
                        bf.put(word);
                        segs.add(word);
                    }
                }
            }
        }
        /**
         *添加segs到segmentation的word列
         *使用Mybatis foreach标签
         */
        tDao.insert1(segs);
    }

    @Test
    /**
     * @author: optimjie
     * @description: 分表按照segId的最后两位来分，这样可以保证每个表是比较均匀的。
     *  因为在关系表很大的时候，主要的瓶颈在于找到所有包含某一个segId的data再将所有的tf值加起来比较大小
     * @date: 2022-05-23 11:01
     */
    public void addAllSegUseSplit() {
        //将segmentation表的全部结果按照 id 和 word 返回给 segmentations
        //select * from segmentation
        List<Segmentation> segmentations = segmentationService.queryAllSeg();

        //分词到分词id的映射
        //Map中的value是String类型 key是char(string?)类型
        Map<String, Integer> wordToId = new HashMap<>(1000000);
        int count_test=0;
        for (Segmentation seg : segmentations) {
            wordToId.put(seg.getWord(), seg.getId());
        }


        if (stopWordsSet == null) {
            stopWordsSet = new HashSet<>();
            loadStopWords(stopWordsSet, this.getClass().getResourceAsStream("/jieba/stop_words.txt"));
        }

        Map<Integer, List<T>> mp = new HashMap<>(100000);
        int cnt = 0;

        //双层for循环 为了遍历data表中的每一个数据
        for (int loop = 0; loop < 300; loop++) {
            System.out.println("loop=====" + loop);

            //10000
            //将某一第offset页的limit条数据返回给records
            //select * from data limit #{limit} offset #{offset}
            List<Record> records = recordService.selectPartialRecords(1000, Math.max(0, loop * 1000));

            //遍历data表某一页中的 1000条的数据
            for (int i = loop * 1000; i < (loop + 1) * 1000; i++) {
                //i 对1000取余了 因此不管i是多少，都能保证在records的索引范围内
                Record record = records.get(i % 1000);
                String caption = record.getCaption();
                // 某一句话的分词
                List<SegToken> segTokens = jiebaSegmenter.process(caption, JiebaSegmenter.SegMode.INDEX);
                //返回这个句子中的5个关键词
                List<Keyword> keywords = tfidfAnalyzer.analyze(caption,5);
                //
                Map<String, T> countMap = new HashMap<>();

                // 构建 countMap
                for (SegToken segToken : segTokens) {
                    String word = segToken.word;
                    if (stopWordsSet.contains(word)) continue;  // 判断是否是停用词
                    int segId = wordToId.get(word);  //分词id
                    int dataId = record.getId();    //该分词所在的记录的id

                    //计算关联度 如果分词不是keywords 则关联度为0
                    //         如果分词是keywords 则关联度为Tfidfvalue
                    double tf = 0;                  //分词与句子的关联度
                    for (Keyword v : keywords) {
                        if (v.getName().equals(word)) {
                            tf = v.getTfidfvalue();
                            break;
                        }
                    }

                    //更新分词在某一caption中出现的次数

                    if (!countMap.containsKey(word)){
                        int count = 1;
                        countMap.put(word, new T(dataId, segId, tf, count));
                    } else {
                        //T是一个自定义的实体类
                        T t = countMap.get(word);
                        int count = t.getCount();
                        t.setCount(++count);
                        countMap.put(word,t);
                    }
                }

                /**
                 * countMap的清零更新频率是一个句子更新一次
                 * mp的清零更新频率是100000个分词更新一次
                 *下面这个for循环作用就是将countMap中的值以分词为单位，传递给mp
                 * 他这里有一个冗余的地方就是他要先把mp中的某一个key位置的值取出来，放到list中，然后将新值放到list中，再将list中放回mp中
                 * 他这样的目的是保证在发生哈希碰撞的时候，不会将两个相同的值给更新掉？？？？
                 * 感觉很有可能是这个原因
                 * 这样就可以保证句子分词之间重复不会被替换掉的问题了
                 *
                 * 不对不对，他是用put方法，将list值put上去的。按理说有可能发生哈希碰撞，而且会比较t对象的值是否重复
                 *
                 * 他为什么不把t的值直接put给HashMap????
                 *
                 * */
                for (T t : countMap.values()) {
                    int segId = t.getSegId();
                    int idx = segId % 100;   //以分词id分表
                    List list = mp.getOrDefault(idx, new ArrayList<>(10000));
                    list.add(t);
                    mp.put(idx, list);
                    cnt++;   //
                }
                if (cnt > 100000) {  // 之所以这么搞，是因为在最后直接insert的话，会爆堆空间，虽然我已经开了4个G但好像还是不行。
                    cnt = 0;
                    System.out.println("=====cnt=====");
                    for (Integer idx : mp.keySet()) {
                        String tableName = "data_seg_relation_" + idx;
                        tDao.createNewTable(tableName);
                        tDao.insert2(mp.get(idx), tableName);
                    }
                    mp = new HashMap<>(100000);
                }

            }
        }

        if (cnt > 0) {
            for (Integer idx : mp.keySet()) {
                String tableName = "data_seg_relation_" + idx;
                tDao.createNewTable(tableName);
                tDao.insert2(mp.get(idx), tableName);
            }
        }
    }

    private void loadStopWords(Set<String> set, InputStream in){
        BufferedReader bufr;
        try
        {
            bufr = new BufferedReader(new InputStreamReader(in));
            String line=null;
            while((line=bufr.readLine())!=null) {
                set.add(line.trim());
            }
            try
            {
                bufr.close();
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

}
