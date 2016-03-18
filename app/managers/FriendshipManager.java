package managers;

import models.*;
import models.enums.LinkType;
import models.services.ElasticsearchService;
import play.db.jpa.JPA;

import javax.inject.Inject;
import javax.persistence.NoResultException;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/**
 * Created by Iven on 17.12.2015.
 */
public class FriendshipManager implements BaseManager {

    @Inject
    ElasticsearchService elasticsearchService;

    @Inject
    NotificationManager notificationManager;

    @Inject
    GroupAccountManager groupAccountManager;

    @Override
    public void create(Object model) {
        JPA.em().persist(model);
    }

    @Override
    public void update(Object model) {
        JPA.em().merge(model);

        // each account document contains information about their friends
        // if a user accepts a friendship -> (re)index this.account document
        try {
            elasticsearchService.index(model);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void delete(Object model) {
        Friendship friendship = ((Friendship) model);
        JPA.em().remove(friendship);

        notificationManager.deleteReferences(friendship);

        // each account document contains information about their friends
        // if a user deletes his friendship -> (re)index this.account document
        try {
            elasticsearchService.index(friendship);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Friendship findById(Long id) {
        return JPA.em().find(Friendship.class, id);
    }

    public Friendship findRequest(Account me, Account potentialFriend) {
        try{
            return (Friendship) JPA.em().createQuery("SELECT fs FROM Friendship fs WHERE fs.account.id = ?1 AND fs.friend.id = ?2 AND fs.linkType = ?3")
                    .setParameter(1, me.id).setParameter(2, potentialFriend.id).setParameter(3, LinkType.request).getSingleResult();
        } catch (NoResultException exp) {
            return null;
        }
    }

    public Friendship findReverseRequest(Account me, Account potentialFriend) {
        try{
            return (Friendship) JPA.em().createQuery("SELECT fs FROM Friendship fs WHERE fs.friend.id = ?1 AND fs.account.id = ?2 AND fs.linkType = ?3")
                    .setParameter(1, me.id).setParameter(2, potentialFriend.id).setParameter(3, LinkType.request).getSingleResult();
        } catch (NoResultException exp) {
            return null;
        }
    }

    public Friendship findFriendLink(Account account, Account target) {
        try{
            return (Friendship) JPA.em().createQuery("SELECT fs FROM Friendship fs WHERE fs.account.id = ?1 and fs.friend.id = ?2 AND fs.linkType = ?3")
                    .setParameter(1, account.id).setParameter(2, target.id).setParameter(3, LinkType.establish).getSingleResult();
        } catch (NoResultException exp) {
            return null;
        }
    }

    /**
     * Returns true, if two accounts have a friendly relationship.
     *
     * @param me Account instance
     * @param potentialFriend Account instance
     * @return True, if both accounts are friends
     */
    public static boolean alreadyFriendly(Account me, Account potentialFriend) {
        try {
            JPA.em().createQuery("SELECT fs FROM Friendship fs WHERE fs.account.id = ?1 and fs.friend.id = ?2 AND fs.linkType = ?3")
                    .setParameter(1, me.id).setParameter(2, potentialFriend.id).setParameter(3, LinkType.establish).getSingleResult();
        } catch (NoResultException exp) {
            return false;
        }
        return true;
    }

    public boolean alreadyRejected(Account me, Account potentialFriend) {
        try {
            JPA.em().createQuery("SELECT fs FROM Friendship fs WHERE fs.account.id = ?1 and fs.friend.id = ?2 AND fs.linkType = ?3")
                    .setParameter(1, me.id).setParameter(2, potentialFriend.id).setParameter(3, LinkType.reject).getSingleResult();
        } catch (NoResultException exp) {
            return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public List<Account> findFriends(final Account account){
        return (List<Account>) JPA.em().createQuery("SELECT fs.friend FROM Friendship fs WHERE fs.account.id = ?1 AND fs.linkType = ?2 ORDER BY fs.friend.firstname ASC")
                .setParameter(1, account.id).setParameter(2, LinkType.establish).getResultList();
    }

    /**
     * Lists all friendships of the specified account (in both directions and of all LinkTypes)
     * @param accountId the account id
     * @return the list of Friendships
     */
    @SuppressWarnings("unchecked")
    public List<Friendship> listAllFriendships(Long accountId){
        return JPA.em().createQuery("SELECT fs FROM Friendship fs WHERE fs.account.id = ?1 OR fs.friend.id = ?1")
                .setParameter(1, accountId).getResultList();
    }

    public static List<Long> findFriendsId(final Account account){
        return (List<Long>) JPA.em().createQuery("SELECT fs.friend.id FROM Friendship fs WHERE fs.account.id = ?1 AND fs.linkType = ?2")
                .setParameter(1, account.id).setParameter(2, LinkType.establish).getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<Friendship> findRequests(final Account account) {
        return (List<Friendship>) JPA.em().createQuery("SELECT fs FROM Friendship fs WHERE (fs.friend.id = ?1 OR fs.account.id = ?1) AND fs.linkType = ?2")
                .setParameter(1, account.id).setParameter(2, LinkType.request).getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<Friendship> findRejects(final Account account) {
        return (List<Friendship>) JPA.em().createQuery("SELECT fs FROM Friendship fs WHERE fs.account.id = ?1 AND fs.linkType = ?2")
                .setParameter(1, account.id).setParameter(2, LinkType.reject).getResultList();
    }

    public List<Account> friendsToInvite(Account account, Group group) {
        List<Account> inevitableFriends = findFriends(account);

        if (inevitableFriends != null) {
            Iterator<Account> it = inevitableFriends.iterator();
            Account friend;

            while(it.hasNext()) {
                friend = it.next();

                //remove account from list if there is any type of link (requests, invite, already member)
                if (groupAccountManager.hasLinkTypes(friend, group)) {
                    it.remove();
                }
            }
        }

        return inevitableFriends;
    }
}
