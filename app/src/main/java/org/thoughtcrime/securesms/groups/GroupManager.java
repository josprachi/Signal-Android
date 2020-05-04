package org.thoughtcrime.securesms.groups;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.signal.zkgroup.groups.UuidCiphertext;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.thoughtcrime.securesms.util.Util;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class GroupManager {

  private static final String TAG = Log.tag(GroupManager.class);

  @WorkerThread
  public static @NonNull GroupActionResult createGroup(@NonNull  Context        context,
                                                       @NonNull  Set<Recipient> members,
                                                       @Nullable Bitmap         avatar,
                                                       @Nullable String         name,
                                                                 boolean        mms)
      throws GroupChangeBusyException, GroupChangeFailedException, IOException
  {
    boolean          shouldAttemptToCreateV2 = !mms && FeatureFlags.CREATE_V2_GROUPS;
    Set<RecipientId> memberIds               = getMemberIds(members);

    if (shouldAttemptToCreateV2) {
      try {
        try (GroupManagerV2.GroupCreator groupCreator = new GroupManagerV2(context).create()) {
          return groupCreator.createGroup(memberIds, name, BitmapUtil.toByteArray(avatar));
        }
      } catch (MembershipNotSuitableForV2Exception e) {
        Log.w(TAG, "Attempted to make a GV2, but membership was not suitable, falling back to GV1", e);

        return GroupManagerV1.createGroup(context, memberIds, avatar, name, false);
      }
    } else {
      return GroupManagerV1.createGroup(context, memberIds, avatar, name, mms);
    }
  }

  @WorkerThread
  public static GroupActionResult updateGroup(@NonNull  Context context,
                                              @NonNull  GroupId groupId,
                                              @Nullable byte[]  avatar,
                                                        boolean avatarChanged,
                                              @Nullable String  name)
    throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
    if (groupId.isV2()) {
      try (GroupManagerV2.GroupEditor edit = new GroupManagerV2(context).edit(groupId.requireV2())) {
        return edit.updateGroupTitleAndAvatar(name, avatarChanged ? avatar : null);
      }
    } else {
      List<Recipient> members = DatabaseFactory.getGroupDatabase(context)
                                               .getGroupMembers(groupId, GroupDatabase.MemberSet.FULL_MEMBERS_EXCLUDING_SELF);

      return updateGroup(context, groupId, new HashSet<>(members), avatar, name);
    }
  }

  public static @Nullable GroupActionResult updateGroup(@NonNull  Context        context,
                                                        @NonNull  GroupId        groupId,
                                                        @NonNull  Set<Recipient> members,
                                                        @Nullable byte[]         avatar,
                                                        @Nullable String         name)
  {
    if (groupId.isV2()) {
      throw new AssertionError("Use other methods for GV2");
    }

    Set<RecipientId> addresses = getMemberIds(members);

    return GroupManagerV1.updateGroup(context, groupId, addresses, avatar, name);
  }

  private static Set<RecipientId> getMemberIds(Collection<Recipient> recipients) {
    final Set<RecipientId> results = new HashSet<>();
    for (Recipient recipient : recipients) {
      results.add(recipient.getId());
    }

    return results;
  }

  @WorkerThread
  public static boolean leaveGroup(@NonNull Context context, @NonNull GroupId.Push groupId)
      throws GroupChangeBusyException, GroupChangeFailedException, IOException
  {
    if (groupId.isV2()) {
      try (GroupManagerV2.GroupEditor edit = new GroupManagerV2(context).edit(groupId.requireV2())) {
        edit.leaveGroup();
        Log.i(TAG, "Left group " + groupId);
        return true;
      } catch (GroupInsufficientRightsException e) {
        Log.w(TAG, "Unexpected prevention from leaving " + groupId + " due to rights", e);
        return false;
      } catch (GroupNotAMemberException e) {
        Log.w(TAG, "Already left group " + groupId, e);
        return true;
      }
    } else {
      return GroupManagerV1.leaveGroup(context, groupId.requireV1());
    }
  }

  @WorkerThread
  public static void updateGroupFromServer(@NonNull Context context,
                                           @NonNull GroupId.V2 groupId,
                                           int version)
      throws GroupChangeBusyException, IOException, GroupNotAMemberException
  {
    try (GroupManagerV2.GroupEditor edit = new GroupManagerV2(context).edit(groupId)) {
      edit.updateLocalToServerVersion(version);
    }
  }

  @WorkerThread
  public static void ejectFromGroup(@NonNull Context context, @NonNull GroupId.V2 groupId, @NonNull Recipient recipient)
      throws GroupChangeBusyException, GroupChangeFailedException, GroupInsufficientRightsException, GroupNotAMemberException, IOException
  {
    try (GroupManagerV2.GroupEditor edit = new GroupManagerV2(context).edit(groupId.requireV2())) {
      edit.ejectMember(recipient.getId());
      Log.i(TAG, "Left group " + groupId);
    }
  }

  @WorkerThread
  public static boolean setMemberAdmin(@NonNull Context context,
                                       @NonNull GroupId.V2 groupId,
                                       @NonNull RecipientId recipientId,
                                       boolean admin)
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.setMemberAdmin(recipientId, admin);
      return true;
    } catch (IOException | GroupChangeBusyException | GroupNotAMemberException | GroupInsufficientRightsException | GroupChangeFailedException e) {
      Log.w(TAG, e);
      return false;
    }
  }

  @WorkerThread
  public static void updateProfileKey(@NonNull Context context, @NonNull GroupId.V2 groupId)
      throws IOException, GroupChangeBusyException, GroupInsufficientRightsException, GroupNotAMemberException, GroupChangeFailedException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.updateSelfProfileKeyInGroup();
    }
  }

  @WorkerThread
  public static void acceptInvite(@NonNull Context context, @NonNull GroupId.V2 groupId)
      throws GroupChangeBusyException, GroupChangeFailedException, GroupNotAMemberException, GroupInsufficientRightsException, IOException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.acceptInvite();
    }
  }

  @WorkerThread
  public static void updateGroupTimer(@NonNull Context context, @NonNull GroupId.Push groupId, int expirationTime)
      throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
    if (groupId.isV2()) {
      try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
        editor.updateGroupTimer(expirationTime);
      }
    } else {
      GroupManagerV1.updateGroupTimer(context, groupId.requireV1(), expirationTime);
    }
  }

  @WorkerThread
  public static void cancelInvites(@NonNull Context context,
                                   @NonNull GroupId.V2 groupId,
                                   @NonNull Collection<UuidCiphertext> uuidCipherTexts)
      throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.cancelInvites(uuidCipherTexts);
    }
  }

  @WorkerThread
  public static void applyMembershipAdditionRightsChange(@NonNull Context context,
                                                         @NonNull GroupId.V2 groupId,
                                                         @NonNull GroupAccessControl newRights)
       throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.updateMembershipRights(newRights);
    }
  }

  @WorkerThread
  public static void applyAttributesRightsChange(@NonNull Context context,
                                                 @NonNull GroupId.V2 groupId,
                                                 @NonNull GroupAccessControl newRights)
      throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.updateAttributesRights(newRights);
    }
  }

  public static void addMembers(@NonNull Context context,
                                @NonNull GroupId.Push groupId,
                                @NonNull Collection<RecipientId> newMembers)
      throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException, MembershipNotSuitableForV2Exception
  {
    if (groupId.isV2()) {
      try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
        editor.addMembers(newMembers);
      }
    } else {
      GroupDatabase.GroupRecord groupRecord = DatabaseFactory.getGroupDatabase(context).requireGroup(groupId);
      List<RecipientId>         members     = groupRecord.getMembers();
      byte[]                    avatar      = Util.readFully(AvatarHelper.getAvatar(context, groupRecord.getRecipientId()));
      Set<RecipientId>          addresses   = new HashSet<>(members);

      addresses.addAll(newMembers);
      GroupManagerV1.updateGroup(context, groupId, addresses, avatar, groupRecord.getTitle());
    }
  }

  public static class GroupActionResult {
    private final Recipient groupRecipient;
    private final long      threadId;

    public GroupActionResult(Recipient groupRecipient, long threadId) {
      this.groupRecipient = groupRecipient;
      this.threadId       = threadId;
    }

    public Recipient getGroupRecipient() {
      return groupRecipient;
    }

    public long getThreadId() {
      return threadId;
    }
  }
}
