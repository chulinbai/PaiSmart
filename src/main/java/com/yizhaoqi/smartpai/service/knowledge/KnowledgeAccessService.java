package com.yizhaoqi.smartpai.service.knowledge;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.repository.OrganizationTagRepository;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;
import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.hash;

/** The same owner/public/parent-organization policy is used for ES prefilters and source checks. */
@Service
public class KnowledgeAccessService {
    private final UserRepository users;
    private final FileUploadRepository files;
    private final OrganizationTagRepository orgs;
    public KnowledgeAccessService(UserRepository users,FileUploadRepository files,OrganizationTagRepository orgs) { this.users=users;this.files=files;this.orgs=orgs; }
    public Access resolve(String identity) {
        if(identity==null || identity.isBlank()) throw new SecurityException("Authentication required");
        User user;
        try { user=users.findById(Long.parseLong(identity)).orElseThrow(()->new SecurityException("Unknown user")); }
        catch(NumberFormatException e) { user=users.findByUsername(identity).orElseThrow(()->new SecurityException("Unknown user")); }
        Set<String> effective=new HashSet<>();effective.add("DEFAULT");
        Deque<String> pending=new ArrayDeque<>();
        if(user.getOrgTags()!=null) for(String tag:user.getOrgTags().split(",")) if(!tag.isBlank()) pending.add(tag.trim());
        Set<String> visited=new HashSet<>();
        while(!pending.isEmpty()) {
            String tag=pending.removeFirst();if(!visited.add(tag)) continue;
            effective.add(tag);
            orgs.findByTagId(tag).ifPresent(org->{if(org.getParentTag()!=null && !org.getParentTag().isBlank()) pending.add(org.getParentTag());});
        }
        return new Access(user.getId().toString(),effective);
    }
    public boolean readable(FileUpload file,Access access) {
        return file!=null && (file.isPublic() || access.userId().equals(file.getUserId()) || access.orgTags().contains(file.getOrgTag()));
    }
    public FileUpload requireRead(long id,Access access) {
        FileUpload file=files.findById(id).orElseThrow(()->new SecurityException("Document unavailable"));
        if(!readable(file,access)) throw new SecurityException("Document unavailable"); return file;
    }
    public FileUpload requireOwner(long id,Access access) {
        FileUpload file=requireRead(id,access);
        if(!access.userId().equals(file.getUserId())) throw new SecurityException("Only the document owner may rebuild or delete it"); return file;
    }
    public List<FileUpload> documents(Access access,List<Long> requested) {
        if(!requested.isEmpty()) return requested.stream().map(id->requireRead(id,access)).toList();
        // MySQL collations can equate distinct tags (e.g. DEFAULT/default). Use the same
        // exact predicate as explicit-document checks and Elasticsearch before returning sources.
        return files.findAccessibleFilesWithTags(access.userId(),new ArrayList<>(access.orgTags()))
                .stream().filter(file->readable(file,access)).toList();
    }
    public String scope(FileUpload file) { return file.isPublic()?hash("PUBLIC"):hash("OWNER_OR_ORG",file.getUserId(),file.getOrgTag()); }
    public boolean sameScope(FileUpload file,String scope) { return scope(file).equals(scope); }
}
