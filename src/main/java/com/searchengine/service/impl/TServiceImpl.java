package com.searchengine.service.impl;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import com.searchengine.dao.SegmentationDao;
import com.searchengine.dao.TDao;
import com.searchengine.dto.Record;
import com.searchengine.entity.RecordSeg;
import com.searchengine.entity.Segmentation;
import com.searchengine.entity.T;
import com.searchengine.service.TService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class TServiceImpl implements TService {
    @Autowired
    private TDao tDao;
    @Autowired
    private SegmentationDao segmentationDao;
    @Override
    public boolean insert1(List<String> segs) {
        tDao.insert1(segs);
        return true;
    }
    @Override
    public boolean insert2(List<T> relations, String tableName) {
        tDao.insert2(relations, tableName);
        return true;
    }

    @Override
    public int getMaxId() {
        return tDao.getMaxId();
    }

    @Override
    public Map<String, Object> getRcord(String searchInfo, int pageSize, int pageNum) {
        // int offset = pageSize * (pageNum - 1);
        // StringBuilder sb = new StringBuilder();
        // JiebaSegmenter segmenter = new JiebaSegmenter();
        // List<SegToken> segTokens = segmenter.process(searchInfo, JiebaSegmenter.SegMode.SEARCH);
        // boolean first = true;
        // for (int i = 0; i < segTokens.size(); i++) {
        //     if (segmentationDao.selectOneSeg(segTokens.get(i).word) == null) continue;
        //     int segId = segmentationDao.selectOneSeg(segTokens.get(i).word).getId();
        //     if (first) { sb.append(segId); first = false; }
        //     else sb.append(',').append(segId);
        // }
        // System.out.println(sb.toString().equals(""));
        // if(sb.toString().equals("")){
        //     return null;
        // } else {
        //     List<Record> records = tDao.getRecord(sb.toString(), pageSize, offset);
        //     int recordsNum = tDao.getRecordsNum(sb.toString());
        //     Map<String, Object> mp = new HashMap<>();
        //     mp.put("recordsNum", recordsNum);
        //     mp.put("records", records);
        //     return mp;
        // }
        return null;
    }

    @Override
    public Map<String, Object> getRcordUseSplit(String searchInfo, int pageSize, int pageNum) {
        int offset = pageSize * (pageNum - 1);
        StringBuilder sb = new StringBuilder();
        JiebaSegmenter segmenter = new JiebaSegmenter();

        // -----处理过滤词-----start
        String[] words = searchInfo.split("\\s+");
        List<String> filterWord = new ArrayList<>();
        boolean find = false;
        int filterWordIndex = -1;
        for (int i = 0; i < words.length; i++) {
            String str = words[i];
            if (Pattern.matches("^-.*?$", str)) {
                if (!find) {
                    filterWordIndex = searchInfo.indexOf(str);
                    find = true;
                }
                filterWord.add(str.substring(1));
            }

        }
        if (filterWordIndex != -1) {
            searchInfo = searchInfo.substring(0, filterWordIndex);
        }
        // -----处理过滤词-----end

        // JiebaSegmenter.process()  将一个汉字序列切分成一个一个单独的词
        // "我不喜欢日本和服。"
        // [[我, 0, 1], [不, 1, 2], [喜欢, 2, 4], [日本, 4, 6], [和服, 6, 8], [。, 8, 9]]
        // “我”的左边是0索引，右边是1索引

        List<SegToken> segTokens = segmenter.process(searchInfo, JiebaSegmenter.SegMode.SEARCH);
        boolean first = true;
        for (int i = 0; i < segTokens.size(); i++) {
//            if (segmentationDao.selectOneSeg(segTokens.get(i).word) == null) continue;
//            if (segmentationDao.selectListSeg(segTokens.get(i).word) == null) continue;
            //去除空
            if ("".equals(segTokens.get(i).word.trim())) continue;

            //SELECT * FROM segmentation WHERE word=#{word}
            //word是用户输入的查询关键词，进行分词处理后的，某一个分词
            List<Segmentation> segmentationList = segmentationDao.selectListSeg(segTokens.get(i).word);
            for (Segmentation segmentation : segmentationList) {
                int segId = segmentation.getId();
                int idx = segId % 100;
                /**
                 * if else 语句是为了利用union对SQL进行拼接
                 * word='足球'
                 * select * from data_seg_relation_98 where seg_id = 1087598
                 * union
                 * select * from data_seg_relation_96 where seg_id = 1375596
                 * union
                 * select * from data_seg_relation_94 where seg_id = 1663594
                 * union
                 * select * from data_seg_relation_92 where seg_id = 1951592
                 * union
                 * select * from data_seg_relation_90 where seg_id = 2239590
                 * union
                 * select * from data_seg_relation_88 where seg_id = 2527588
                 * union
                 * select * from data_seg_relation_86 where seg_id = 2815586
                 * union
                 * select * from data_seg_relation_84 where seg_id = 3103584
                 * union
                 * select * from data_seg_relation_82 where seg_id = 3391582
                 * */
                if (first) {
                    sb.append("select * from data_seg_relation_").append(idx).append(" where seg_id = ").append(segId).append('\n');
                    first = false;
                } else {
                    sb.append("union").append('\n');
                    sb.append("select * from data_seg_relation_").append(idx).append(" where seg_id = ").append(segId).append('\n');
                }
            }
//            int segId = segmentationDao.selectOneSeg(segTokens.get(i).word).getId();
        }
        String info = sb.toString();
        String filterInfo = "";
        if ("".equals(info)) return null;
        boolean filterWordInSegmentation = false;
        if (filterWord.size() > 0) {
            sb.delete(0, sb.length());
            boolean fi = true;
            for (int i = 0; i < filterWord.size(); i++) {

                //if (segmentationDao.selectOneSeg(filterWord.get(i)) == null) continue;
                if ("".equals(filterWord.get(i).trim())) continue;
                filterWordInSegmentation = true;
                List<Segmentation> segmentationList = segmentationDao.selectListSeg(filterWord.get(i));

                for (Segmentation segmentation : segmentationList) {
                    int segId = segmentation.getId();
                    int idx = segId % 100;
                    if (fi) {
                        sb.append("select * from data_seg_relation_").append(idx).append(" where seg_id = ").append(segId).append('\n');
                        fi = false;
                    } else {
                        sb.append("union").append('\n');
                        sb.append("select * from data_seg_relation_").append(idx).append(" where seg_id = ").append(segId).append('\n');
                    }
                }
                // int segId = segmentationDao.selectOneSeg(filterWord.get(i)).getId();
            }
            filterInfo = sb.toString();
        }
        List records = null;
        int recordsNum = 0;
        if (filterWord.size() > 0 && filterWordInSegmentation) {
            records = tDao.getRecordUseSplitFilter(info, filterInfo, pageSize, offset);
            recordsNum = tDao.getRecordsNumFilter(info, filterInfo);
        } else {
            records = tDao.getRecordUseSplit(info, pageSize, offset);
            recordsNum = tDao.getRecordsNum(info);
        }
        Map<String, Object> mp = new HashMap<>();
        mp.put("recordsNum", recordsNum);
        mp.put("records", records);
        return mp;
    }

    @Override
    public int createNewTable(String tableName) {
        tDao.createNewTable(tableName);
        return 0;
    }
}
