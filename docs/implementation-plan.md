# LEETGRIND
## A Gamified Social Platform for Competitive Programming

**Tagline:** Compete. Solve. Level Up.

### 1. Project Context

LeetGrind is a social companion to LeetCode for students and early-career developers preparing for technical interviews. Its primary use case is a group of friends or classmates who want to practice data structures and algorithms (DSA) consistently and see how they are progressing toward shared goals.

Users solve problems and submit code on LeetCode. LeetGrind uses eligible public submission activity to show personal progress and support group competition. The features and stack below describe the planned application.

### 2. Problem Statement

Keeping up a practice routine is hard when everyone solves problems separately. LeetCode submission history shows what an individual submitted. A study group that wants to run a weekly challenge still has to agree on what counts, collect everyone's progress, and work out the standings. When that work lives in group chats and spreadsheets, recording progress takes extra effort and standings can fall out of date.

LeetGrind should make progress visible to the learner and their group shortly after they practice. A learner can see what they accomplished and where they have practiced less. Peers can pursue the same goals and compare results using shared scoring rules, with eligible activity credited automatically.

### 3. Proposed Solution

LeetGrind is a gamified social platform that connects users' publicly accessible LeetCode activity with a competitive environment where they can create or join coding groups, earn experience points (XP), complete challenges, and compete on leaderboards.

Users connect their LeetCode profiles to LeetGrind. The system periodically retrieves publicly accessible submission information through LeetCode's GraphQL interface, processes eligible accepted submissions, and updates the user's progress.

The platform converts coding activity into a reward system based on problem difficulty. Users earn XP, maintain streaks, complete quests, unlock achievements, and compete against other members.

For example, a student joins a private group with an invite code and solves a medium problem on LeetCode. When LeetGrind syncs the eligible accepted submission, it awards 25 XP and includes that activity in the group's standings if the submission occurred after the student joined and falls within the leaderboard period. The student can see when their profile last synced. Other members see the student's LeetGrind identity in the standings.

### 4. Main Objectives

1. **Improve consistency:** Encourage users to practice DSA regularly through streaks, daily quests, and rewards.
2. **Enable social competition:** Allow users to create and join public or private coding groups.
3. **Automate progress tracking:** Retrieve and process eligible LeetCode submissions without requiring manual updates.
4. **Introduce gamification:** Reward coding achievements through XP, levels, badges, and milestones.
5. **Encourage collaborative learning:** Provide group challenges and shared coding goals.
6. **Measure improvement:** Present analytics that help users understand their coding activity and topic-wise performance.
7. **Provide personalized guidance:** Recommend coding problems based on a user's previous activity, topic performance, and difficulty level.

### 5. Major Features and Functionalities

#### 5.1 User Authentication and Profile Management
- Secure registration and login.
- JWT-based authentication.
- User profile with XP, level, streak, and coding statistics.
- LeetCode profile connection.
- Privacy controls for personal information.

#### 5.2 Group Management
- Users can create public or private coding groups.
- Members can join groups using invitation codes.
- Group owners can manage members and group settings.
- Each group has its own leaderboard and activity overview.
- LeetCode usernames are not exposed to other group members.
- Only eligible coding activity occurring after a user joins a group contributes to that group's competition.

#### 5.3 LeetCode Integration and Automatic Tracking
- Connect a user's LeetCode profile.
- Periodically retrieve publicly accessible submission information.
- Identify accepted submissions and their problem difficulty.
- Synchronize coding activity with the platform.
- Prevent duplicate submission processing and duplicate XP rewards.
- Display the last successful synchronization time and synchronization status.

#### 5.4 XP and Leveling System

Users earn experience points based on the difficulty of successfully solved problems.

| Activity | XP Reward |
|---|---:|
| Easy problem | 10 XP |
| Medium problem | 25 XP |
| Hard problem | 50 XP |
| Problem of the Day completion | +50 XP |
| Daily quest completion | +30 XP |
| Weekly quest completion | +100 XP |
| Daily login streak | 5 XP |

Users accumulate XP to progress through levels and unlock achievements. The system maintains an XP transaction history to ensure rewards are traceable and duplicate rewards are prevented.

#### 5.5 Leaderboard System
- Weekly leaderboard.
- Monthly leaderboard.
- All-time leaderboard.
- Group-specific leaderboards.
- Ranking based on eligible XP earned during the relevant period.
- Display user rankings and progress within their groups.

#### 5.6 Problem of the Day (POTD)
- Display a daily featured coding problem.
- Show problem title, difficulty, topics, and LeetCode link.
- Award bonus XP for completing the featured problem within the eligible period.
- Track individual and group completion statistics.
- Maintain daily participation streaks.

#### 5.7 Coding Challenges and Duels
- One-on-one coding competitions.
- Group-based coding challenges.
- Challenges based on problem difficulty or the number of problems solved.
- Custom deadlines and completion conditions.
- Display challenge progress and results.

#### 5.8 Daily and Weekly Quests
- Complete a specified number of coding problems.
- Solve problems from selected DSA topics.
- Reach weekly problem-solving targets.
- Maintain consistent coding activity.
- Earn bonus XP upon successful completion.

#### 5.9 Streaks and Achievements
- Track consecutive days of eligible coding activity.
- Display current and longest streaks.
- Unlock badges for milestones and achievements.
- Maintain an activity calendar showing coding consistency.
- Reward users for reaching predefined milestones.

#### 5.10 Activity Feed and Notifications
- Display recent coding activity.
- Notify users when challenges are completed.
- Show achievement unlocks and milestones.
- Display relevant group activity and leaderboard changes.
- Provide in-app notifications for invitations and challenge updates.

#### 5.11 Personal Analytics
- Total problems solved by difficulty.
- Weekly and monthly coding activity.
- XP progression over time.
- Current and longest streak.
- Topic-wise performance.
- Active coding days and consistency trends.

#### 5.12 Personalized Problem Recommendations
- Recommend problems based on previously solved problems.
- Identify topics where users have limited practice.
- Suggest suitable difficulty levels.
- Avoid recommending problems the user has already solved.
- Use performance-based rules to generate relevant recommendations.

### 6. Technology Stack

| Component | Technology |
|---|---|
| Frontend | React.js, TypeScript |
| Backend | Java, Spring Boot |
| Database | MySQL |
| ORM | Spring Data JPA, Hibernate |
| Authentication | Spring Security, JWT |
| Caching | Redis |
| External Integration | LeetCode GraphQL interface |
| API Documentation | Swagger / OpenAPI |
| Containerization | Docker |
| Version Control | Git, GitHub |

The application will initially follow a modular monolithic architecture, keeping related functionalities organized into independent backend modules while maintaining a single deployable application.

### 7. Expected Outcomes

The expected outcome is a functional web application that transforms individual coding practice into a structured and competitive social experience.

The platform will:

- Encourage students to maintain consistent DSA practice.
- Provide a competitive environment through groups and leaderboards.
- Automatically track eligible coding activity.
- Reward users through XP, levels, quests, and achievements.
- Help users understand their coding progress through analytics.
- Encourage friendly competition and accountability among peers.

From a technical perspective, the project will demonstrate practical implementation of REST APIs, authentication and authorization, relational database design, caching, external API integration, background processing, transaction management, and scalable backend design.

### 8. Scope and Limitations

- LeetGrind will use publicly accessible LeetCode information through an unofficial GraphQL interface.
- LeetCode does not provide a guaranteed stable public API for this integration. Changes, access restrictions, throttling, or service failures may affect synchronization.
- The platform will not execute, compile, or evaluate users' submitted code.
- Actual problem-solving and submission evaluation will remain on LeetCode.
- Only eligible and verifiable accepted submissions will contribute to rewards.
- Duplicate submissions and previously earned rewards will be handled to prevent XP manipulation.
- Historical activity before joining a group will not contribute to that group's competition.
- AI-based recommendations and advanced coaching may be considered future enhancements rather than requirements for the initial implementation.

### 9. Proposed Implementation Sequence

1. **Validate the submission data.** Check what the public LeetCode interface returns for submission identity, acceptance, timestamps, problem difficulty, and history coverage. Use the results to define eligibility and decide how to handle activity the integration cannot verify.
2. **Build the practice-to-leaderboard flow.** Implement accounts, profile linking, sync status, an XP transaction history, invitation-based group membership, and a weekly group leaderboard. Process each eligible reward once so overlapping syncs and retries leave totals consistent.
3. **Build on the same activity data.** Add streaks, the Problem of the Day, quests, and analytics. Extend the group experience with more leaderboard periods, challenges, duels, and notifications. Use topic and difficulty history for rule-based problem recommendations.

The first milestone is complete when a student can join a group, solve a problem on LeetCode, and see the expected XP and leaderboard update after sync. Verify that retrying the sync leaves XP totals unchanged, that eligible submissions made before joining are excluded from group standings, and that other group members see only the student's LeetGrind identity. A failed sync should show its status while preserving the last successful sync time and credited activity.

### 10. Conclusion

LeetGrind aims to make DSA preparation more engaging by combining the problem-solving ecosystem of LeetCode with social competition, gamification, and progress tracking.

By introducing coding groups, automated submission tracking, XP rewards, leaderboards, daily challenges, quests, streaks, and analytics, the platform seeks to address the motivation and consistency challenges faced by students preparing for technical interviews.

The project also provides an opportunity to develop a practical full-stack application while applying important software engineering concepts, particularly backend architecture, database management, security, caching, and third-party service integration.
