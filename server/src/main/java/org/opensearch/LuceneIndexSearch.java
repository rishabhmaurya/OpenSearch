package org.opensearch;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class LuceneIndexSearch {
    public static void main(String[] args) {
        String indexPathDocReordered = "/Users/rishma/demo/reordered";
        String indexPathDocRegular = "/Users/rishma/demo/regular/index";

        String termToSearch = "france";

        String field = "request";

        try {
            Directory directory = FSDirectory.open(Paths.get(indexPathDocReordered));
            IndexReader indexReader = DirectoryReader.open(directory);

            Iterator<LeafReaderContext> itr = indexReader.leaves().iterator();
            while(itr.hasNext()) {
                try (LeafReader leafReader = itr.next().reader()) {
// 206 404 400 500
                    System.out.println(leafReader.getDocCount(field));
                    PostingsEnum postingsEnum = leafReader.postings(new Term(field, termToSearch));
                    if (postingsEnum != null) {
                        // Collect document IDs
                        List<Integer> docIDs = new ArrayList<>();
                        int docID;
                        while ((docID = postingsEnum.nextDoc()) != PostingsEnum.NO_MORE_DOCS) {
                            docIDs.add(docID);
                        }

                        // Output the document IDs
                        System.out.println("Document IDs for term '" + termToSearch + "':");
                        System.out.println("total: " + docIDs.size());
                        for (Integer id : docIDs) {
                            System.out.print(id + " ");
                        }
                        System.out.println();
                    } else {
                        System.out.println("Term not found in the index.");
                    }
                }
            }

            // Close the index reader
            directory.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
