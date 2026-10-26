package com.yizhaoqi.smartpai.service.knowledge;

import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.repository.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeAccessServiceTest {
    @Test void databaseCollationCannotBroadenTheExactAuthorizationPolicy() {
        var files=mock(FileUploadRepository.class);
        var service=new KnowledgeAccessService(mock(UserRepository.class),files,mock(OrganizationTagRepository.class));
        var denied=new FileUpload();denied.setId(10L);denied.setUserId("2");denied.setOrgTag("default");
        var own=new FileUpload();own.setId(11L);own.setUserId("1");own.setOrgTag("private");
        var publicFile=new FileUpload();publicFile.setId(12L);publicFile.setUserId("2");publicFile.setPublic(true);
        var exact=new FileUpload();exact.setId(13L);exact.setUserId("2");exact.setOrgTag("DEFAULT");
        when(files.findAccessibleFilesWithTags(eq("1"),anyList())).thenReturn(List.of(denied,own,publicFile,exact));
        var identity=new KnowledgeTypes.Access("1",Set.of("DEFAULT"));
        assertEquals(List.of(own,publicFile,exact),service.documents(identity,List.of()));
        when(files.findById(10L)).thenReturn(Optional.of(denied));
        assertThrows(SecurityException.class,()->service.documents(identity,List.of(10L)));
    }
    @Test void parentOrganizationsAreReadFromCurrentDatabaseAndRevocationIsImmediate() {
        var users=mock(UserRepository.class);var files=mock(FileUploadRepository.class);var orgs=mock(OrganizationTagRepository.class);
        var user=new User();user.setId(1L);user.setUsername("alice");user.setOrgTags("CHILD");
        when(users.findById(1L)).thenReturn(Optional.of(user));
        var child=new OrganizationTag();child.setTagId("CHILD");child.setParentTag("PARENT");when(orgs.findByTagId("CHILD")).thenReturn(Optional.of(child));
        var service=new KnowledgeAccessService(users,files,orgs);var file=new FileUpload();file.setUserId("2");file.setOrgTag("PARENT");
        assertTrue(service.readable(file,service.resolve("1")));
        user.setOrgTags("OTHER");assertFalse(service.readable(file,service.resolve("1")));
        file.setPublic(true);assertTrue(service.readable(file,service.resolve("1")));
    }
    @Test void scopesOnlyMergeEquivalentPoliciesAndMissingAuthorizationFailsClosed() {
        var users=mock(UserRepository.class);var service=new KnowledgeAccessService(users,mock(FileUploadRepository.class),mock(OrganizationTagRepository.class));
        var a=new FileUpload();a.setUserId("1");a.setOrgTag("A");var b=new FileUpload();b.setUserId("2");b.setOrgTag("A");
        assertNotEquals(service.scope(a),service.scope(b));a.setPublic(true);b.setPublic(true);assertEquals(service.scope(a),service.scope(b));
        assertThrows(SecurityException.class,()->service.resolve("missing"));
    }
}
